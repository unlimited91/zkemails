package me.toymail.zkemails.tui;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interactive console menu for browsing encrypted messages.
 * Provides arrow key navigation, thread viewing, and reply functionality.
 */
public final class ConsoleMenu {
    private static final Logger log = LoggerFactory.getLogger(ConsoleMenu.class);

    /**
     * Result action from the menu.
     */
    public enum MenuAction {
        REPLY,      // User wants to reply to a message
        QUIT,       // User quit the menu
        CANCELLED   // Menu was cancelled (e.g., not interactive terminal)
    }

    /**
     * Result from running the menu.
     */
    public record MenuResult(MenuAction action, long selectedMessageId) {
        public static MenuResult quit() {
            return new MenuResult(MenuAction.QUIT, -1);
        }

        public static MenuResult cancelled() {
            return new MenuResult(MenuAction.CANCELLED, -1);
        }

        public static MenuResult reply(long messageId) {
            return new MenuResult(MenuAction.REPLY, messageId);
        }
    }

    private final StoreContext context;
    private final Config config;
    private final IdentityKeys.KeyBundle keys;
    private final String password;
    private final MenuRenderer renderer;
    private final KeyReader keyReader;

    private List<ImapClient.MailSummary> messages;
    private int selectedIndex = 0;

    public ConsoleMenu(StoreContext context, Config config,
                       IdentityKeys.KeyBundle keys, String password) {
        this.context = context;
        this.config = config;
        this.keys = keys;
        this.password = password;
        this.renderer = new MenuRenderer();
        this.keyReader = new KeyReader();
    }

    /**
     * Check if the current terminal supports interactive mode.
     */
    public static boolean isInteractiveSupported() {
        return System.console() != null && RawTerminal.isSupported();
    }

    /**
     * Run the interactive menu.
     * @param imap The IMAP client to use for fetching messages
     * @param limit Maximum number of messages to display
     * @return The result of the menu interaction
     */
    public MenuResult run(ImapClient imap, int limit) {
        if (!isInteractiveSupported()) {
            return MenuResult.cancelled();
        }

        try {
            // Fetch messages
            messages = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", limit);
            if (messages.isEmpty()) {
                System.out.println("No encrypted messages found.");
                return MenuResult.quit();
            }

            // Run the menu
            try (RawTerminal terminal = RawTerminal.enable()) {
                renderer.hideCursor();
                try {
                    return runListLoop(imap);
                } finally {
                    renderer.showCursor();
                    renderer.clearScreen();
                }
            }
        } catch (Exception e) {
            log.error("Menu error: {}", e.getMessage());
            return MenuResult.cancelled();
        }
    }

    /**
     * Main list view loop.
     */
    private MenuResult runListLoop(ImapClient imap) throws Exception {
        while (true) {
            int terminalHeight = RawTerminal.getTerminalHeight();
            renderer.renderListView(messages, selectedIndex, terminalHeight);

            KeyReader.KeyEvent event = keyReader.readKey();

            switch (event.key()) {
                case UP:
                    if (selectedIndex > 0) {
                        selectedIndex--;
                    }
                    break;

                case DOWN:
                    if (selectedIndex < messages.size() - 1) {
                        selectedIndex++;
                    }
                    break;

                case ENTER:
                    long uid = messages.get(selectedIndex).uid();
                    MenuResult threadResult = showThreadView(imap, uid);
                    if (threadResult.action() == MenuAction.REPLY) {
                        return threadResult;
                    }
                    // Otherwise, continue with list view
                    break;

                case QUIT:
                case ESCAPE:
                    return MenuResult.quit();

                default:
                    // Ignore other keys
                    break;
            }
        }
    }

    /**
     * Show thread view for a specific message.
     */
    private MenuResult showThreadView(ImapClient imap, long uid) throws Exception {
        // Build thread
        Set<String> threadIds = imap.buildThreadIdSet(uid);
        List<ImapClient.MailSummary> threadMsgs;

        if (threadIds.isEmpty()) {
            // Single message, no thread
            ImapClient.MailSummary msg = imap.getMessageByUid(uid);
            if (msg != null) {
                threadMsgs = List.of(msg);
            } else {
                return MenuResult.quit();
            }
        } else {
            threadMsgs = imap.searchThread(threadIds, 100);
            if (threadMsgs.isEmpty()) {
                ImapClient.MailSummary msg = imap.getMessageByUid(uid);
                if (msg != null) {
                    threadMsgs = List.of(msg);
                } else {
                    return MenuResult.quit();
                }
            }
        }

        // Decrypt all messages in thread
        List<String> decryptedBodies = new ArrayList<>();
        for (var msg : threadMsgs) {
            String body = decryptMessage(imap, msg);
            decryptedBodies.add(body);
        }

        // Get base subject
        String baseSubject = threadMsgs.isEmpty() ? "" :
                threadMsgs.get(0).subject();
        if (baseSubject != null) {
            baseSubject = baseSubject.replaceAll("(?i)^(Re:\\s*)+", "").trim();
        }

        // Thread view loop with scrolling
        int scrollOffset = 0;

        while (true) {
            int terminalHeight = RawTerminal.getTerminalHeight();
            renderer.renderThreadView(threadMsgs, decryptedBodies, baseSubject,
                    scrollOffset, terminalHeight);

            KeyReader.KeyEvent event = keyReader.readKey();

            switch (event.key()) {
                case UP:
                    if (scrollOffset > 0) {
                        scrollOffset--;
                    }
                    break;

                case DOWN:
                    scrollOffset++;
                    break;

                case REPLY:
                    // Return the last message ID for reply
                    long lastMsgId = threadMsgs.get(threadMsgs.size() - 1).uid();
                    return MenuResult.reply(lastMsgId);

                case QUIT:
                case ESCAPE:
                    // Return to list view
                    return MenuResult.quit();

                default:
                    // Ignore other keys
                    break;
            }
        }
    }

    /**
     * Decrypt a message using stored contact keys.
     */
    private String decryptMessage(ImapClient imap, ImapClient.MailSummary msg) {
        try {
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(msg.uid());

            String ephemX25519PubB64 = first(hdrs, "X-ZKEmails-Ephem-X25519");
            String wrappedKeyB64 = first(hdrs, "X-ZKEmails-WrappedKey");
            String wrappedKeyNonceB64 = first(hdrs, "X-ZKEmails-WrappedKey-Nonce");
            String msgNonceB64 = first(hdrs, "X-ZKEmails-Nonce");
            String ciphertextB64 = first(hdrs, "X-ZKEmails-Ciphertext");
            String sigB64 = first(hdrs, "X-ZKEmails-Sig");
            String recipientFpHex = first(hdrs, "X-ZKEmails-Recipient-Fp");

            String fromEmail = extractEmail(msg.from());
            ContactsStore.Contact contact = context.contacts().get(fromEmail);
            if (contact == null || contact.ed25519PublicB64 == null) {
                log.debug("No contact found for sender: {}", fromEmail);
                return null;
            }

            CryptoBox.EncryptedPayload payload = new CryptoBox.EncryptedPayload(
                    ephemX25519PubB64, wrappedKeyB64, wrappedKeyNonceB64,
                    msgNonceB64, ciphertextB64, sigB64, recipientFpHex
            );

            return CryptoBox.decryptForRecipient(
                    fromEmail,
                    config.email,
                    msg.subject(),
                    payload,
                    keys.x25519PrivateB64(),
                    contact.ed25519PublicB64
            );
        } catch (Exception e) {
            log.debug("Decryption failed for message {}: {}", msg.uid(), e.getMessage());
            return null;
        }
    }

    /**
     * Extract email address from "Name <email>" format.
     */
    private String extractEmail(String from) {
        if (from == null) return null;
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }

    /**
     * Get first header value from map.
     */
    private static String first(Map<String, List<String>> map, String key) {
        List<String> v = map.get(key);
        return (v != null && !v.isEmpty()) ? v.get(0) : null;
    }
}

package me.toymail.zkemails.commands;

import me.toymail.zkemails.service.ContactService;
import me.toymail.zkemails.service.ServiceContext;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Command(name = "contact-versions", aliases = {"cv"},
        description = "View contact version history and diffs",
        footer = {
                "",
                "Examples:",
                "  zke cv                          List all versions",
                "  zke cv --diff latest            Diff latest version with current",
                "  zke cv --diff <filename>        Diff specific version with current",
                "  zke cv --show <filename>        Show contacts in specific version"
        })
public final class ContactVersionsCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ContactVersionsCmd.class);
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final StoreContext context;

    public ContactVersionsCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names = "--diff", description = "Version to diff with current (or 'latest')")
    String diffVersion;

    @Option(names = "--show", description = "Show contacts in specific version")
    String showVersion;

    @Override
    public void run() {
        try {
            if (!context.hasActiveProfile()) {
                log.error("No active profile set. Use 'zke pset <email>' first.");
                return;
            }

            ServiceContext services = ServiceContext.fromStoreContext(context);
            ContactService contactService = services.contacts();

            if (showVersion != null) {
                showVersionContacts(contactService);
            } else if (diffVersion != null) {
                showDiff(contactService);
            } else {
                listVersions(contactService);
            }
        } catch (Exception e) {
            log.error("Failed: {}", e.getMessage());
        }
    }

    private void listVersions(ContactService contactService) throws Exception {
        List<ContactService.VersionInfo> versions = contactService.listVersions();

        if (versions.isEmpty()) {
            log.info("No version history found.");
            log.info("Versions are created automatically when contacts change.");
            return;
        }

        log.info("Contact Version History ({} versions):", versions.size());
        log.info("");
        log.info("{:<40} {:>10} {:>8}", "Filename", "Contacts", "Size");
        log.info("{}", "-".repeat(62));

        for (var v : versions) {
            String displayTime = DISPLAY_FORMATTER.format(v.timestamp());
            log.info("{:<40} {:>10} {:>6}KB",
                    v.filename(),
                    v.contactCount(),
                    v.sizeBytes() / 1024);
        }

        log.info("");
        log.info("Use 'zke cv --diff <filename>' to compare with current");
        log.info("Use 'zke cv --diff latest' to compare most recent version");
    }

    private void showDiff(ContactService contactService) throws Exception {
        List<ContactService.VersionInfo> versions = contactService.listVersions();

        if (versions.isEmpty()) {
            log.error("No version history found.");
            return;
        }

        String versionFilename;
        if ("latest".equalsIgnoreCase(diffVersion)) {
            versionFilename = versions.get(0).filename();
            log.info("Using latest version: {}", versionFilename);
        } else {
            versionFilename = diffVersion;
        }

        List<ContactService.ContactDiff> diffs = contactService.diffWithCurrent(versionFilename);

        if (diffs.isEmpty()) {
            log.info("No differences between {} and current contacts.", versionFilename);
            return;
        }

        log.info("Changes from {} to current:", versionFilename);
        log.info("");

        for (var diff : diffs) {
            switch (diff.changeType()) {
                case "added" -> {
                    log.info("[+] ADDED: {}", diff.email());
                    log.info("    Status: {}", diff.newValue().status());
                    if (diff.newValue().hasKeys()) {
                        log.info("    Keys: present (fingerprint: {}...)",
                                truncate(diff.newValue().fingerprintHex(), 16));
                    }
                }
                case "removed" -> {
                    log.info("[-] REMOVED: {}", diff.email());
                    log.info("    Was status: {}", diff.oldValue().status());
                }
                case "modified" -> {
                    log.info("[~] MODIFIED: {}", diff.email());
                    log.info("    Changed fields: {}", String.join(", ", diff.changedFields()));
                    if (diff.changedFields().contains("fingerprintHex")) {
                        log.info("    !! KEY CHANGE DETECTED !!");
                        log.info("    Old fingerprint: {}...",
                                truncate(diff.oldValue().fingerprintHex(), 16));
                        log.info("    New fingerprint: {}...",
                                truncate(diff.newValue().fingerprintHex(), 16));
                    }
                    if (diff.changedFields().contains("status")) {
                        log.info("    Status: {} -> {}",
                                diff.oldValue().status(), diff.newValue().status());
                    }
                }
            }
            log.info("");
        }

        // Summary
        long added = diffs.stream().filter(d -> "added".equals(d.changeType())).count();
        long removed = diffs.stream().filter(d -> "removed".equals(d.changeType())).count();
        long modified = diffs.stream().filter(d -> "modified".equals(d.changeType())).count();
        log.info("Summary: {} added, {} removed, {} modified", added, removed, modified);
    }

    private void showVersionContacts(ContactService contactService) throws Exception {
        List<ContactService.ContactInfo> contacts = contactService.getContactsInVersion(showVersion);

        if (contacts.isEmpty()) {
            log.info("No contacts in version: {}", showVersion);
            return;
        }

        log.info("Contacts in version {} ({} total):", showVersion, contacts.size());
        log.info("");
        log.info("{:<35} {:>12} {:>8}", "Email", "Status", "Has Keys");
        log.info("{}", "-".repeat(58));

        for (var c : contacts) {
            log.info("{:<35} {:>12} {:>8}",
                    c.email(),
                    c.status(),
                    c.hasKeys() ? "Yes" : "No");
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}

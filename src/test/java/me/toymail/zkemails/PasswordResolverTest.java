package me.toymail.zkemails;

import me.toymail.zkemails.store.CredentialStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Console;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PasswordResolverTest {

    private CredentialStore credentialStore;
    private PasswordResolver resolver;

    @BeforeEach
    void setUp() {
        credentialStore = mock(CredentialStore.class);
        resolver = new PasswordResolver(credentialStore);
    }

    @Test
    public void testResolve_ExplicitPasswordTakesPriority() {
        // Even if keyring has a password, explicit should be used
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.of("keyring-password"));

        String result = resolver.resolve("explicit-password", "user@example.com", null);

        assertEquals("explicit-password", result);
        // Keyring should not even be called
        verify(credentialStore, never()).getPassword(anyString());
    }

    @Test
    public void testResolve_ExplicitPasswordWithWhitespace() {
        String result = resolver.resolve("  password-with-spaces  ", "user@example.com", null);

        assertEquals("  password-with-spaces  ", result);
        verify(credentialStore, never()).getPassword(anyString());
    }

    @Test
    public void testResolve_BlankExplicitFallsToKeyring() {
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.of("keyring-password"));

        String result = resolver.resolve("   ", "user@example.com", null);

        assertEquals("keyring-password", result);
        verify(credentialStore).getPassword("user@example.com");
    }

    @Test
    public void testResolve_NullExplicitFallsToKeyring() {
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.of("keyring-password"));

        String result = resolver.resolve(null, "user@example.com", null);

        assertEquals("keyring-password", result);
        verify(credentialStore).getPassword("user@example.com");
    }

    @Test
    public void testResolve_KeyringPasswordUsedWhenNoExplicit() {
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.of("stored-password"));

        String result = resolver.resolve(null, "user@example.com", null);

        assertEquals("stored-password", result);
    }

    @Test
    public void testResolve_FallsToConsoleWhenNoKeyringPassword() {
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.empty());
        Console console = mock(Console.class);
        when(console.readPassword("Password for %s: ", "user@example.com"))
                .thenReturn("console-password".toCharArray());

        String result = resolver.resolve(null, "user@example.com", console);

        assertEquals("console-password", result);
        verify(console).readPassword("Password for %s: ", "user@example.com");
    }

    @Test
    public void testResolve_ThrowsWhenNoPasswordAndNoConsole() {
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                resolver.resolve(null, "user@example.com", null)
        );

        assertTrue(exception.getMessage().contains("No password available"));
        assertTrue(exception.getMessage().contains("user@example.com"));
    }

    @Test
    public void testResolve_ThrowsWhenConsoleReturnsNull() {
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.empty());
        Console console = mock(Console.class);
        when(console.readPassword("Password for %s: ", "user@example.com")).thenReturn(null);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                resolver.resolve(null, "user@example.com", console)
        );

        assertTrue(exception.getMessage().contains("cancelled"));
    }

    @Test
    public void testResolve_EmptyPasswordFromConsole() {
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.empty());
        Console console = mock(Console.class);
        when(console.readPassword("Password for %s: ", "user@example.com"))
                .thenReturn("".toCharArray());

        String result = resolver.resolve(null, "user@example.com", console);

        assertEquals("", result);
    }

    @Test
    public void testResolve_PriorityChain_ExplicitOverKeyringOverConsole() {
        // Verify full priority chain: explicit > keyring > console
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.of("keyring"));
        Console console = mock(Console.class);
        when(console.readPassword(anyString(), anyString())).thenReturn("console".toCharArray());

        // With explicit - should use explicit
        assertEquals("explicit", resolver.resolve("explicit", "user@example.com", console));
        verify(credentialStore, never()).getPassword(anyString());
        verify(console, never()).readPassword(anyString(), anyString());

        // Without explicit - should use keyring
        reset(credentialStore, console);
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.of("keyring"));
        assertEquals("keyring", resolver.resolve(null, "user@example.com", console));
        verify(credentialStore).getPassword("user@example.com");
        verify(console, never()).readPassword(anyString(), anyString());

        // Without explicit and keyring - should use console
        reset(credentialStore, console);
        when(credentialStore.getPassword("user@example.com")).thenReturn(Optional.empty());
        when(console.readPassword("Password for %s: ", "user@example.com")).thenReturn("console".toCharArray());
        assertEquals("console", resolver.resolve(null, "user@example.com", console));
        verify(credentialStore).getPassword("user@example.com");
        verify(console).readPassword("Password for %s: ", "user@example.com");
    }
}

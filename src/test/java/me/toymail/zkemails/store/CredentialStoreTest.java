package me.toymail.zkemails.store;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CredentialStoreTest {

    @Test
    public void testIsAvailable_WhenKeyringSupported() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);

            CredentialStore store = new CredentialStore();

            assertTrue(store.isAvailable());
        }
    }

    @Test
    public void testIsAvailable_WhenKeyringNotSupported() {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            mockedKeyring.when(Keyring::create).thenThrow(new BackendNotSupportedException("No backend"));

            CredentialStore store = new CredentialStore();

            assertFalse(store.isAvailable());
        }
    }

    @Test
    public void testGetPassword_Success() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            when(keyring.getPassword("zkemails", "user@example.com")).thenReturn("secret123");

            CredentialStore store = new CredentialStore();
            Optional<String> result = store.getPassword("user@example.com");

            assertTrue(result.isPresent());
            assertEquals("secret123", result.get());
            verify(keyring).getPassword("zkemails", "user@example.com");
        }
    }

    @Test
    public void testGetPassword_NotFound() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            when(keyring.getPassword("zkemails", "user@example.com")).thenReturn(null);

            CredentialStore store = new CredentialStore();
            Optional<String> result = store.getPassword("user@example.com");

            assertFalse(result.isPresent());
        }
    }

    @Test
    public void testGetPassword_AccessException() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            when(keyring.getPassword("zkemails", "user@example.com"))
                    .thenThrow(new PasswordAccessException("Access denied"));

            CredentialStore store = new CredentialStore();
            Optional<String> result = store.getPassword("user@example.com");

            assertFalse(result.isPresent());
        }
    }

    @Test
    public void testGetPassword_WhenNotAvailable() {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            mockedKeyring.when(Keyring::create).thenThrow(new BackendNotSupportedException("No backend"));

            CredentialStore store = new CredentialStore();
            Optional<String> result = store.getPassword("user@example.com");

            assertFalse(result.isPresent());
        }
    }

    @Test
    public void testSetPassword_Success() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);

            CredentialStore store = new CredentialStore();
            boolean result = store.setPassword("user@example.com", "newpassword");

            assertTrue(result);
            verify(keyring).setPassword("zkemails", "user@example.com", "newpassword");
        }
    }

    @Test
    public void testSetPassword_AccessException() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            doThrow(new PasswordAccessException("Cannot save"))
                    .when(keyring).setPassword("zkemails", "user@example.com", "newpassword");

            CredentialStore store = new CredentialStore();
            boolean result = store.setPassword("user@example.com", "newpassword");

            assertFalse(result);
        }
    }

    @Test
    public void testSetPassword_WhenNotAvailable() {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            mockedKeyring.when(Keyring::create).thenThrow(new BackendNotSupportedException("No backend"));

            CredentialStore store = new CredentialStore();
            boolean result = store.setPassword("user@example.com", "newpassword");

            assertFalse(result);
        }
    }

    @Test
    public void testDeletePassword_Success() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);

            CredentialStore store = new CredentialStore();
            boolean result = store.deletePassword("user@example.com");

            assertTrue(result);
            verify(keyring).deletePassword("zkemails", "user@example.com");
        }
    }

    @Test
    public void testDeletePassword_AccessException() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            doThrow(new PasswordAccessException("Cannot delete"))
                    .when(keyring).deletePassword("zkemails", "user@example.com");

            CredentialStore store = new CredentialStore();
            boolean result = store.deletePassword("user@example.com");

            assertFalse(result);
        }
    }

    @Test
    public void testDeletePassword_WhenNotAvailable() {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            mockedKeyring.when(Keyring::create).thenThrow(new BackendNotSupportedException("No backend"));

            CredentialStore store = new CredentialStore();
            boolean result = store.deletePassword("user@example.com");

            assertFalse(result);
        }
    }
}

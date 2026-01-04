package me.toymail.zkemails.store;

public final class Config {
    public String email;
    public Imap imap = new Imap();
    public Smtp smtp = new Smtp();

    public static final class Imap {
        public String host = "imap.gmail.com";
        public int port = 993;
        public boolean ssl = true;
        public String username; // defaults to email
    }

    public static final class Smtp {
        public String host = "smtp.gmail.com";
        public int port = 587;
        public String username; // defaults to email
    }
}

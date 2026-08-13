package com.eurobuddha.salon;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;

/** SFTP transport (mwiede JSch fork). One session per uploader instance —
 *  callers batch. Host keys are TOFU: first contact surfaces the SHA256
 *  fingerprint for the user to confirm (HostKeyUnverified carries it); once
 *  pinned in the profile, any change is a hard "possible MITM" refusal. */
final class SftpUploader implements Hosting.Uploader, AutoCloseable {

    /** Thrown on first contact so the UI can show the fingerprint. */
    static class HostKeyUnverified extends Hosting.HostingException {
        final String fingerprint;
        HostKeyUnverified(String fp) {
            super("First connection to this server — verify its host key fingerprint: " + fp);
            this.fingerprint = fp;
        }
    }

    private final Hosting.Profile profile;
    private Session session;
    private ChannelSftp sftp;
    private String seenFp = "";

    SftpUploader(Hosting.Profile p) { this.profile = p; }

    private synchronized ChannelSftp channel() throws Hosting.HostingException {
        if (sftp != null && sftp.isConnected()) return sftp;
        String host = profile.cfgStr("host");
        int port = profile.cfg().optInt("port", 22);
        String user = profile.cfgStr("user");
        String pinnedFp = profile.cfgStr("hostKeyFp");
        try {
            JSch jsch = new JSch();
            if ("key".equals(profile.cfgStr("auth"))) {
                String pem = profile.secret("privateKey");
                if (pem.isEmpty()) throw new Hosting.HostingException("No private key in the profile");
                jsch.addIdentity("atelier", pem.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, null);
            }
            session = jsch.getSession(user, host, port);
            if (!"key".equals(profile.cfgStr("auth"))) {
                session.setPassword(profile.secret("password"));
            }
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setHostKeyRepository(new PinRepo(pinnedFp));
            session.setUserInfo(new QuietUserInfo());
            session.setTimeout(15000);
            session.connect(15000);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10000);
            return sftp;
        } catch (Hosting.HostingException he) {
            close();
            throw he;
        } catch (Exception e) {
            close();
            if (!seenFp.isEmpty() && pinnedFp.isEmpty()) throw new HostKeyUnverified(seenFp);
            if (!seenFp.isEmpty() && !seenFp.equals(pinnedFp)) {
                throw new Hosting.HostingException("HOST KEY CHANGED for " + host
                        + " — possible MITM. Server now presents " + seenFp
                        + ". Re-verify your server before re-pinning.");
            }
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // "Auth cancel/fail" = the server refused the credential we sent.
            // For password auth that usually means the server only accepts a
            // key (common for root) — point the user at the key option.
            if (msg.toLowerCase().contains("auth")) {
                boolean keyMode = "key".equals(profile.cfgStr("auth"));
                throw new Hosting.HostingException(keyMode
                        ? "Server rejected the private key for " + user + "@" + host
                          + ". Check the key is correct, unencrypted, and authorised for this user."
                        : "Server rejected the password for " + user + "@" + host
                          + ". Many servers accept ONLY key auth (especially for root) — "
                          + "switch Authentication to Private key and paste your key.");
            }
            throw new Hosting.HostingException("SFTP connect failed: "
                    + host + ":" + port + " — " + msg);
        }
    }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        ChannelSftp ch = channel();
        String root = Hosting.trimSlash(profile.cfgStr("remoteRoot"));
        String full = root + "/" + relPath;
        try {
            mkdirs(ch, full.substring(0, full.lastIndexOf('/')));
            ch.put(new ByteArrayInputStream(bytes), full);
        } catch (SftpException e) {
            throw new Hosting.HostingException("SFTP upload failed at " + relPath + ": " + e.getMessage());
        }
        return Hosting.publicUrl(profile, relPath, false);
    }

    @Override public boolean exists(String relPath) throws Hosting.HostingException {
        ChannelSftp ch = channel();
        String root = Hosting.trimSlash(profile.cfgStr("remoteRoot"));
        try {
            SftpATTRS a = ch.stat(root + "/" + relPath);
            return a != null;
        } catch (SftpException e) {
            return false;   // no such file
        }
    }

    private static void mkdirs(ChannelSftp ch, String dir) {
        String[] parts = dir.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) { path.append("/"); continue; }
            path.append(part).append("/");
            String d = path.substring(0, path.length() - 1);
            try { ch.stat(d); } catch (SftpException e) {
                try { ch.mkdir(d); } catch (SftpException ignored) { }
            }
        }
    }

    @Override public synchronized void close() {
        try { if (sftp != null) sftp.disconnect(); } catch (Exception ignored) { }
        try { if (session != null) session.disconnect(); } catch (Exception ignored) { }
        sftp = null; session = null;
    }

    /** Pins exactly one fingerprint; records what the server presented. */
    private class PinRepo implements HostKeyRepository {
        private final String pinned;
        PinRepo(String pinned) { this.pinned = pinned == null ? "" : pinned; }

        @Override public int check(String host, byte[] key) {
            seenFp = sha256Fp(key);
            if (pinned.isEmpty()) return NOT_INCLUDED;
            return pinned.equals(seenFp) ? OK : CHANGED;
        }
        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "atelier-pin"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    static String sha256Fp(byte[] key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        } catch (Exception e) {
            return "SHA256:?";
        }
    }

    private static class QuietUserInfo implements UserInfo {
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) { }
    }
}

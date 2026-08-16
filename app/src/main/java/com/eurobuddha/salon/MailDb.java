package com.eurobuddha.salon;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local DM store — conversations (by peer msgpk) + messages, deduped by coinid. Simplified
 * from support/freezepeach/.../Db.java (1:1 only, no groups for v1). Purely a device-side
 * cache of what's on-chain; the chain is the source of truth.
 */
final class MailDb extends SQLiteOpenHelper {

    private static MailDb INSTANCE;
    static synchronized MailDb get(Context c) {
        if (INSTANCE == null) INSTANCE = new MailDb(c.getApplicationContext());
        return INSTANCE;
    }

    private MailDb(Context c) { super(c, "salon_mail", null, 4); }

    /** Delivery status of an outgoing message. Inbound messages are always SENT. */
    static final int SENT = 1, PENDING = 0;

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts(peerpk TEXT PRIMARY KEY, handle TEXT, avatar TEXT, addr TEXT, "
                + "mxaddr TEXT, lastbody TEXT, lastts INTEGER, unread INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE messages(coinid TEXT PRIMARY KEY, peerpk TEXT, mine INTEGER, body TEXT, "
                + "media TEXT, mime TEXT, ts INTEGER, valid INTEGER, status INTEGER DEFAULT 1, "
                + "replybody TEXT, replyfrom TEXT)");
        db.execSQL("CREATE INDEX ix_msg_peer ON messages(peerpk, ts)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        // v2: the peer's Maxima addresses (CSV), learned from their profile.json.
        // Presence of mxaddr is what makes a contact Maxima-capable for DMs.
        if (o < 2) db.execSQL("ALTER TABLE contacts ADD COLUMN mxaddr TEXT");
        // v3: per-message delivery status. Existing rows default to SENT (1) so
        // history isn't retroactively marked pending.
        if (o < 3) db.execSQL("ALTER TABLE messages ADD COLUMN status INTEGER DEFAULT 1");
        // v4: quoted-reply snapshot (denormalised: the reply carries a copy of the
        // parent's text + author, so rendering the quote needs no cross-device lookup).
        if (o < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replybody TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN replyfrom TEXT");
        }
    }

    static final class Contact { String peerpk, handle, avatar, addr, mxaddr, lastbody; long lastts; int unread; }
    static final class Message { String coinid, peerpk, body, media, mime, replyBody, replyFrom; boolean mine, valid; long ts; int status = SENT; }

    /** Insert a message; returns true if new (dedup by coinid). */
    boolean insert(String coinid, String peerpk, boolean mine, String body, String media, String mime, long ts, boolean valid) {
        return insert(coinid, peerpk, mine, body, media, mime, ts, valid, SENT);
    }

    /** Insert with an explicit delivery status (PENDING for an unconfirmed send).
     *  ATOMIC dedup: relies on the INSERT's conflict result, not a prior SELECT —
     *  the coin NOTIFY path and scanMail can race on the same coin, and a
     *  check-then-insert let both see "new" and double-notify. */
    boolean insert(String coinid, String peerpk, boolean mine, String body, String media, String mime, long ts, boolean valid, int status) {
        ContentValues v = new ContentValues();
        v.put("coinid", coinid); v.put("peerpk", peerpk); v.put("mine", mine ? 1 : 0);
        v.put("body", body); v.put("media", media); v.put("mime", mime); v.put("ts", ts); v.put("valid", valid ? 1 : 0);
        v.put("status", status);
        long row = getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return row != -1;   // -1 = the row already existed (conflict ignored) → not new
    }

    /** Mark an outgoing message SENT (delivery confirmed / posted). */
    void setStatus(String coinid, int status) {
        ContentValues v = new ContentValues(); v.put("status", status);
        getWritableDatabase().update("messages", v, "coinid=?", new String[]{coinid});
    }

    /** Outgoing messages not yet confirmed delivered — the retry outbox. */
    List<Message> pendingOutbox() {
        List<Message> out = new ArrayList<>();
        Cursor cur = getReadableDatabase().rawQuery(
                "SELECT coinid,peerpk,mine,body,media,mime,ts,valid,status,replybody,replyfrom FROM messages "
                + "WHERE mine=1 AND status=" + PENDING + " ORDER BY ts ASC", null);
        while (cur.moveToNext()) out.add(readMsg(cur));
        cur.close();
        return out;
    }

    void upsertContact(String peerpk, String handle, String avatar, String addr, String lastbody, long lastts, boolean incUnread) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor cur = db.rawQuery("SELECT unread FROM contacts WHERE peerpk=?", new String[]{peerpk});
        boolean exists = cur.moveToFirst();
        int unread = exists ? cur.getInt(0) : 0; cur.close();
        if (incUnread) unread++;
        ContentValues v = new ContentValues();
        v.put("peerpk", peerpk);
        if (handle != null && !handle.isEmpty()) v.put("handle", handle);
        if (avatar != null && !avatar.isEmpty()) v.put("avatar", avatar);
        if (addr != null && !addr.isEmpty()) v.put("addr", addr);
        v.put("lastbody", lastbody); v.put("lastts", lastts); v.put("unread", unread);
        db.insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        db.update("contacts", v, "peerpk=?", new String[]{peerpk});   // ensure fields updated on conflict
    }

    List<Contact> contacts() {
        List<Contact> out = new ArrayList<>();
        Cursor cur = getReadableDatabase().rawQuery(
                "SELECT peerpk,handle,avatar,addr,mxaddr,lastbody,lastts,unread FROM contacts ORDER BY lastts DESC", null);
        while (cur.moveToNext()) {
            Contact c = new Contact();
            c.peerpk = cur.getString(0); c.handle = cur.getString(1); c.avatar = cur.getString(2);
            c.addr = cur.getString(3); c.mxaddr = cur.getString(4);
            c.lastbody = cur.getString(5); c.lastts = cur.getLong(6); c.unread = cur.getInt(7);
            out.add(c);
        }
        cur.close();
        return out;
    }

    Contact contact(String peerpk) {
        Cursor cur = getReadableDatabase().rawQuery(
                "SELECT peerpk,handle,avatar,addr,mxaddr,lastbody,lastts,unread FROM contacts WHERE peerpk=?", new String[]{peerpk});
        Contact c = null;
        if (cur.moveToFirst()) {
            c = new Contact(); c.peerpk = cur.getString(0); c.handle = cur.getString(1); c.avatar = cur.getString(2);
            c.addr = cur.getString(3); c.mxaddr = cur.getString(4);
            c.lastbody = cur.getString(5); c.lastts = cur.getLong(6); c.unread = cur.getInt(7);
        }
        cur.close();
        return c;
    }

    List<Message> messages(String peerpk) {
        List<Message> out = new ArrayList<>();
        Cursor cur = getReadableDatabase().rawQuery(
                "SELECT coinid,peerpk,mine,body,media,mime,ts,valid,status,replybody,replyfrom FROM messages WHERE peerpk=? ORDER BY ts ASC", new String[]{peerpk});
        while (cur.moveToNext()) out.add(readMsg(cur));
        cur.close();
        return out;
    }

    private static Message readMsg(Cursor cur) {
        Message m = new Message();
        m.coinid = cur.getString(0); m.peerpk = cur.getString(1); m.mine = cur.getInt(2) == 1;
        m.body = cur.getString(3); m.media = cur.getString(4); m.mime = cur.getString(5);
        m.ts = cur.getLong(6); m.valid = cur.getInt(7) == 1;
        m.status = cur.getColumnCount() > 8 ? cur.getInt(8) : SENT;
        m.replyBody = cur.getColumnCount() > 9 ? cur.getString(9) : null;
        m.replyFrom = cur.getColumnCount() > 10 ? cur.getString(10) : null;
        return m;
    }

    /** Attach a quoted-reply snapshot to an already-inserted message. */
    void setReply(String coinid, String replyBody, String replyFrom) {
        if (replyBody == null || replyBody.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("replybody", replyBody); v.put("replyfrom", replyFrom == null ? "" : replyFrom);
        getWritableDatabase().update("messages", v, "coinid=?", new String[]{coinid});
    }

    /** Record (or clear, with "") the peer's Maxima addresses from their profile. */
    void setMxAddr(String peerpk, String mxaddr) {
        ContentValues v = new ContentValues();
        v.put("peerpk", peerpk);
        v.put("mxaddr", mxaddr == null ? "" : mxaddr);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        db.update("contacts", v, "peerpk=?", new String[]{peerpk});
    }

    void clearUnread(String peerpk) {
        ContentValues v = new ContentValues(); v.put("unread", 0);
        getWritableDatabase().update("contacts", v, "peerpk=?", new String[]{peerpk});
    }

    int totalUnread() {
        Cursor cur = getReadableDatabase().rawQuery("SELECT SUM(unread) FROM contacts", null);
        int n = cur.moveToFirst() ? cur.getInt(0) : 0; cur.close();
        return n;
    }
}

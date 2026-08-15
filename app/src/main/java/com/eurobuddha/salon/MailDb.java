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

    private MailDb(Context c) { super(c, "salon_mail", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts(peerpk TEXT PRIMARY KEY, handle TEXT, avatar TEXT, addr TEXT, "
                + "mxaddr TEXT, lastbody TEXT, lastts INTEGER, unread INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE messages(coinid TEXT PRIMARY KEY, peerpk TEXT, mine INTEGER, body TEXT, "
                + "media TEXT, mime TEXT, ts INTEGER, valid INTEGER)");
        db.execSQL("CREATE INDEX ix_msg_peer ON messages(peerpk, ts)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        // v2: the peer's Maxima addresses (CSV), learned from their profile.json.
        // Presence of mxaddr is what makes a contact Maxima-capable for DMs.
        if (o < 2) db.execSQL("ALTER TABLE contacts ADD COLUMN mxaddr TEXT");
    }

    static final class Contact { String peerpk, handle, avatar, addr, mxaddr, lastbody; long lastts; int unread; }
    static final class Message { String coinid, peerpk, body, media, mime; boolean mine, valid; long ts; }

    /** Insert a message; returns true if new (dedup by coinid). */
    boolean insert(String coinid, String peerpk, boolean mine, String body, String media, String mime, long ts, boolean valid) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor cur = db.rawQuery("SELECT 1 FROM messages WHERE coinid=?", new String[]{coinid});
        boolean exists = cur.moveToFirst(); cur.close();
        if (exists) return false;
        ContentValues v = new ContentValues();
        v.put("coinid", coinid); v.put("peerpk", peerpk); v.put("mine", mine ? 1 : 0);
        v.put("body", body); v.put("media", media); v.put("mime", mime); v.put("ts", ts); v.put("valid", valid ? 1 : 0);
        db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return true;
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
                "SELECT coinid,peerpk,mine,body,media,mime,ts,valid FROM messages WHERE peerpk=? ORDER BY ts ASC", new String[]{peerpk});
        while (cur.moveToNext()) {
            Message m = new Message();
            m.coinid = cur.getString(0); m.peerpk = cur.getString(1); m.mine = cur.getInt(2) == 1;
            m.body = cur.getString(3); m.media = cur.getString(4); m.mime = cur.getString(5);
            m.ts = cur.getLong(6); m.valid = cur.getInt(7) == 1;
            out.add(m);
        }
        cur.close();
        return out;
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

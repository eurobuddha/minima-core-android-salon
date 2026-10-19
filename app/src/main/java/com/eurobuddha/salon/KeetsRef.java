package com.eurobuddha.salon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Salon's public file names on the existing Keets drive. No executable URL input. */
final class KeetsRef {
    static final long PROFILE_LIMIT = 24L * 1024 * 1024;
    static final int JSON_LIMIT = 1024 * 1024;
    static final int IMAGE_LIMIT = 8 * 1024 * 1024;
    static final String FULL = "Your hosted profile can use up to 24 MB, including its media. Remove some media or choose a smaller file.";
    private static final Pattern PROFILE = Pattern.compile("keets://([0-9a-f]{64})(/salon/[a-z0-9-]{1,40}/profile\\.json)");
    private static final Pattern MEDIA = Pattern.compile("keets://([0-9a-f]{64})(/salon/[a-z0-9-]{1,40}/media/([1-9][0-9]{0,7})-([0-9a-f]{64})\\.(jpg|png|webp|gif|mp4|mp3))\\?hash=([0-9a-f]{64})");
    final String url, drive, path, hash, mime;
    final long size;
    final boolean profile;
    private KeetsRef(String u, String d, String p, String h, String m, long s, boolean json) {
        url=u; drive=d; path=p; hash=h; mime=m; size=s; profile=json;
    }
    static KeetsRef parse(String ref) {
        if (ref == null || ref.length() > 512) throw new IllegalArgumentException("Invalid Salon Keets link");
        Matcher p=PROFILE.matcher(ref);
        if(p.matches()) return new KeetsRef(ref,p.group(1),p.group(2),"","application/json",JSON_LIMIT,true);
        Matcher m=MEDIA.matcher(ref);
        if(!m.matches() || !m.group(4).equals(m.group(6))) throw new IllegalArgumentException("Invalid Salon Keets link");
        long size=Long.parseLong(m.group(3));
        String ext=m.group(5);
        if(size>PROFILE_LIMIT || (!ext.equals("mp4") && !ext.equals("mp3") && size>IMAGE_LIMIT))
            throw new IllegalArgumentException(FULL);
        return new KeetsRef(ref,m.group(1),m.group(2),m.group(6),mime(ext),size,false);
    }
    static boolean isRef(String value) { try { parse(value); return true; } catch(RuntimeException invalid) { return false; } }
    static String mime(String ext) {
        switch(ext) { case "mp4":return "video/mp4"; case "mp3":return "audio/mpeg";
            case "jpg":return "image/jpeg"; default:return "image/"+ext; }
    }
    static String extension(String mime) {
        switch(mime) { case "image/jpeg":return "jpg";case "image/png":return "png";
            case "image/webp":return "webp";case "image/gif":return "gif";
            case "video/mp4":return "mp4";case "audio/mpeg":return "mp3";
            default:throw new IllegalArgumentException("Choose a photo, MP4 video or MP3 audio file"); }
    }
    static String sha(byte[] bytes) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out=new StringBuilder(64);
            for(byte b:hash) out.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            return out.toString();
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String mediaPath(String handle, byte[] bytes, String mime) {
        return "/salon/"+Hosting.slug(handle)+"/media/"+bytes.length+"-"+sha(bytes)+"."+extension(mime);
    }
    static void verify(KeetsRef ref, byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>ref.size || (!ref.profile &&
                (bytes.length!=ref.size || !sha(bytes).equals(ref.hash))))
            throw new IllegalArgumentException("The hosted file is incomplete or failed its integrity check");
    }
    /** Count unique Keets media references anywhere in the public JSON, plus the JSON itself. */
    static long usage(JSONObject profile) {
        byte[] json=profile.toString().getBytes(StandardCharsets.UTF_8);
        if(json.length>JSON_LIMIT) throw new IllegalArgumentException("Profile text is too large (maximum 1 MB)");
        Set<String> refs=new HashSet<>(); collect(profile,refs,0);
        long total=json.length;
        for(String ref:refs) {
            KeetsRef parsed=parse(ref);
            if(parsed.profile) continue;
            total+=parsed.size;
            if(total>PROFILE_LIMIT) throw new IllegalArgumentException(FULL);
        }
        return total;
    }
    /** Check the resulting draft, so replacing an avatar does not count both versions. */
    static void checkAddition(JSONObject profile, String slot, String handle, byte[] bytes, String mime) throws Exception {
        JSONObject draft=new JSONObject(profile.toString());
        String ref="keets://"+String.format(java.util.Locale.ROOT,"%064d",0)+mediaPath(handle,bytes,mime)+"?hash="+sha(bytes);
        parse(ref);
        if("avatar".equals(slot) || "banner".equals(slot)) draft.put(slot,ref);
        else {
            JSONArray gallery=draft.optJSONArray("gallery");
            if(gallery==null) { gallery=new JSONArray();draft.put("gallery",gallery); }
            gallery.put(new JSONObject().put("url",ref).put("type",mime).put("caption",""));
        }
        usage(draft);
    }
    private static void collect(Object value, Set<String> refs, int depth) {
        if(depth>32) throw new IllegalArgumentException("Profile is too deeply nested");
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value;
            java.util.Iterator<String> keys=o.keys();
            while(keys.hasNext()) collect(o.opt(keys.next()),refs,depth+1);
        } else if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)collect(a.opt(i),refs,depth+1);
        } else if(value instanceof String && ((String)value).startsWith("keets:")) refs.add((String)value);
    }
}

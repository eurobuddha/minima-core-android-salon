package com.eurobuddha.salon;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Public Salon files only; uses the existing Keets publish/fetch API. */
final class KeetsHostUploader implements Hosting.Uploader {
    KeetsHostUploader(Hosting.Profile ignored) { }
    public String putFile(byte[] bytes,String relPath,String mime) throws Hosting.HostingException {
        try {
            if(relPath==null || !relPath.matches("[a-z0-9-]{1,40}/(?:profile\\.json|media/[A-Za-z0-9._-]+)"))
                throw new IllegalArgumentException("Device hosting is for public Salon profiles and media only");
            if(bytes==null || bytes.length==0 || bytes.length>KeetsRef.PROFILE_LIMIT) throw new IllegalArgumentException(KeetsRef.FULL);
            String handle=relPath.substring(0,relPath.indexOf('/'));
            boolean profile=relPath.endsWith("/profile.json");
            if(profile) KeetsRef.usage(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
            String path=profile ? "/salon/"+handle+"/profile.json" : KeetsRef.mediaPath(handle,bytes,mime);
            KeetsLink.Session live=KeetsLink.ready();
            String expected="keets://"+live.drive+path+(profile ? "" : "?hash="+KeetsRef.sha(bytes));
            KeetsRef.parse(expected); // also enforces the image-reader ceiling before sending
            JSONObject result=new JSONObject(new String(live.request("POST","/media"+path,bytes,4096),StandardCharsets.UTF_8));
            String resultUrl=result.getString("url");
            String pinned="keets://"+live.drive+path+"?hash="+KeetsRef.sha(bytes);
            if(!pinned.equals(resultUrl) || result.optLong("size",-1)!=bytes.length)
                throw new IllegalArgumentException("Keets did not confirm the published file");
            // Stable profile pointer is authenticated by the drive; media remains hash-pinned.
            byte[] verified=live.request("GET","/media?url="+java.net.URLEncoder.encode(pinned,"UTF-8")+"&maxbytes="+bytes.length,null,bytes.length);
            if(!KeetsRef.sha(bytes).equals(KeetsRef.sha(verified))) throw new IllegalArgumentException("Published file verification failed");
            return expected;
        } catch(Exception error) { throw new Hosting.HostingException(error.getMessage()); }
    }
    public boolean exists(String path) { return false; } // immutable media uses its content hash
}

package com.eurobuddha.salon;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Authenticated loopback API documented in Keets SERVICES.md. The existing node
 * FILE bridge supplies its per-boot capability; it is never persisted or shared.
 * Media I/O runs independently of the node's transaction command queue. */
final class KeetsLink {
    private static volatile Context app;
    private static volatile NodeApi node;
    private static volatile Session session;
    static void connect(Context context, NodeApi api) { app=context.getApplicationContext();node=api;session=null; }
    static void release(NodeApi api) { if(node==api) { node=null;session=null;app=null; } }
    interface Request { void send(NodeApi.Cb callback); }
    private static JSONObject await(Request request) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("Media I/O must run in the background");
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try { request.send(new NodeApi.Cb() {
                public void onResult(JSONObject json) { result.complete(json); }
                public void onError(String message) { result.completeExceptionally(new IOException(message)); }
            }); } catch(RuntimeException error) { result.completeExceptionally(error); }
        });
        final JSONObject reply;
        try { reply=result.get(35,TimeUnit.SECONDS); }
        catch(TimeoutException unavailable) { throw new IOException("Keets did not respond. Check that your node is running and try again."); }
        catch(ExecutionException error) { throw new IOException(error.getCause().getMessage()); }
        if(!reply.optBoolean("status",false)) throw new IOException(reply.optString("error",reply.optString("message","Keets is unavailable")));
        return reply;
    }
    static synchronized Session ready() throws Exception {
        if(session!=null) return session;
        final NodeApi api=node; final Context ctx=app;
        if(api==null || ctx==null) throw new IOException("Open Salon with your Keets node running");
        JSONObject reply=await(cb -> api.cmd("keets action:info",30_000,cb));
        JSONObject info=reply.getJSONObject("response");
        if(!info.optBoolean("boundedMedia",false)) throw new IOException("Update your Keets node to use Salon device hosting");
        int port=info.getInt("apiport");
        String drive=info.getString("salondrive").replaceFirst("^0x","");
        if(port<1024 || port>65535 || !drive.matches("[0-9a-f]{64}")) throw new IOException("Invalid Keets service response");
        JSONObject file=await(cb -> api.file("get","keets/apitoken",null,null,cb));
        Uri uri=Uri.parse(file.getString("uri"));
        boolean internal="com.eurobuddha.pandamonium".equals(ctx.getPackageName());
        if(!"content".equals(uri.getScheme()) && !(internal && "file".equals(uri.getScheme())))
            throw new IOException("Invalid node file response");
        byte[] raw;
        try(InputStream input=ctx.getContentResolver().openInputStream(uri)) { raw=read(input,128,Long.MAX_VALUE); }
        String token=new String(raw,StandardCharsets.US_ASCII).trim();
        java.util.Arrays.fill(raw,(byte)0);
        if(!token.matches("[0-9a-f]{64}")) throw new IOException("Keets is starting. Try again shortly.");
        Session candidate=new Session(port,token,drive);
        JSONObject check=new JSONObject(new String(candidate.request("GET","/info",null,4096),StandardCharsets.UTF_8));
        if(!drive.equals(check.optString("salondrive").replaceFirst("^0x","")) || !check.optBoolean("boundedMedia"))
            throw new IOException("The node changed. Reopen Salon and try again.");
        if(node!=api) throw new IOException("The node connection changed. Try again.");
        session=candidate;return candidate;
    }
    static final class Session {
        final int port; private final String token; final String drive;
        Session(int p,String t,String d) { port=p;token=t;drive=d; }
        byte[] request(String method,String route,byte[] body,int cap) throws Exception {
            if(!route.startsWith("/") || route.contains("\r") || route.contains("\n")) throw new IOException("Invalid media request");
            HttpURLConnection con=(HttpURLConnection)new URL("http","127.0.0.1",port,route).openConnection(Proxy.NO_PROXY);
            java.util.Timer watchdog=new java.util.Timer("SalonKeetsDeadline",true);
            watchdog.schedule(new java.util.TimerTask() { public void run() { con.disconnect(); } },50_000);
            try {
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(5000);con.setReadTimeout(40_000);
                con.setRequestMethod(method);con.setRequestProperty("x-keets-token",token);
                con.setRequestProperty("Accept-Encoding","identity");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
                if(body!=null) {
                    if(body.length>KeetsRef.PROFILE_LIMIT) throw new IOException(KeetsRef.FULL);
                    con.setDoOutput(true);con.setFixedLengthStreamingMode(body.length);
                    try(OutputStream out=con.getOutputStream()) { out.write(body); }
                }
                int code=con.getResponseCode();
                if(code==401) { session=null;throw new IOException("Keets restarted. Try again."); }
                if(code!=200) throw new IOException("The media is unavailable. Its host may be offline; try again later.");
                long size=con.getContentLengthLong();
                if(size>cap) throw new IOException("The hosted file exceeds its size limit");
                try(InputStream input=con.getInputStream()) { return read(input,cap,deadline); }
            } finally { watchdog.cancel();con.disconnect(); }
        }
    }
    static byte[] read(InputStream input,int cap,long deadline) throws IOException {
        if(input==null) throw new IOException("The hosted file is unavailable");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1) {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new java.io.InterruptedIOException("Media request stopped");
            if(count>cap-out.size()) throw new IOException("The hosted file exceeds its size limit");
            out.write(buffer,0,count);
        }
        return out.toByteArray();
    }
    static byte[] fetch(String value) throws Exception {
        KeetsRef ref=KeetsRef.parse(value);
        Session live=ready();
        byte[] bytes=live.request("GET","/media?url="+URLEncoder.encode(value,"UTF-8")+"&maxbytes="+ref.size,null,(int)ref.size);
        KeetsRef.verify(ref,bytes);return bytes;
    }
    private KeetsLink() { }
}

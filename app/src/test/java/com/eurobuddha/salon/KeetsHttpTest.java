package com.eurobuddha.salon;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise the real loopback client without a node, wallet or external network. */
public class KeetsHttpTest {
    private void response(String reply, boolean succeeds, int cap) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<String> received=worker.submit(()->{
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(3000);
                    ByteArrayOutputStream header=new ByteArrayOutputStream();
                    InputStream input=socket.getInputStream();
                    while(header.size()<8192) {
                        int b=input.read();if(b<0)break;header.write(b);
                        if(header.toString("US-ASCII").endsWith("\r\n\r\n"))break;
                    }
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                    return header.toString("US-ASCII");
                }
            });
            try {
                KeetsLink.Session client=new KeetsLink.Session(server.getLocalPort(),"b".repeat(64),"a".repeat(64));
                if(succeeds) assertArrayEquals(new byte[]{1,2,3},client.request("GET","/media?test=1",null,cap));
                else assertThrows(IOException.class,()->client.request("GET","/media?test=1",null,cap));
                String request=received.get(4,TimeUnit.SECONDS).toLowerCase(java.util.Locale.ROOT);
                assertTrue(request.contains("x-keets-token: "+"b".repeat(64)));
                assertTrue(request.startsWith("get /media?test=1 http/1.1"));
            } finally { worker.shutdownNow(); }
        }
    }
    @Test public void authenticatedLoopbackReadChecksLength() throws Exception {
        response("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\n\u0001\u0002\u0003",true,3);
        response("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\n\u0001\u0002\u0003",false,2);
    }
    @Test public void redirectCannotForwardNodeCapability() throws Exception {
        response("HTTP/1.1 302 Found\r\nLocation: http://example.invalid/steal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",false,32);
    }
}

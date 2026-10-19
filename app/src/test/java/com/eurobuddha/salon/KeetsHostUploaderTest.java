package com.eurobuddha.salon;

import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class KeetsHostUploaderTest {
    @Test public void profileLinkStaysStableAndReadbackIsVerified() throws Exception {
        byte[] bytes=new JSONObject().put("name","Synthetic profile").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String drive="a".repeat(64), stable="keets://"+drive+"/salon/owner/profile.json";
        String pinned=stable+"?hash="+KeetsRef.sha(bytes);
        KeetsLink.Session session=mock(KeetsLink.Session.class,withSettings().useConstructor(7106,"b".repeat(64),drive));
        when(session.request(eq("POST"),eq("/media/salon/owner/profile.json"),eq(bytes),eq(4096)))
                .thenReturn(new JSONObject().put("url",pinned).put("size",bytes.length).toString().getBytes());
        when(session.request(eq("GET"),contains("maxbytes="+bytes.length),isNull(),eq(bytes.length))).thenReturn(bytes);
        try(MockedStatic<KeetsLink> link=mockStatic(KeetsLink.class)) {
            link.when(KeetsLink::ready).thenReturn(session);
            assertEquals(stable,new KeetsHostUploader(Hosting.Profile.fresh(Hosting.TYPE_KEETS)).putFile(bytes,"owner/profile.json","application/json"));
            verify(session).request(eq("GET"),contains(java.net.URLEncoder.encode(pinned,"UTF-8")),isNull(),eq(bytes.length));
        }
    }
    @Test public void mediaLinkIsContentPinnedAndCorruptReadbackFails() throws Exception {
        byte[] bytes={1,2,3,4};String drive="a".repeat(64);
        String path=KeetsRef.mediaPath("owner",bytes,"image/jpeg"),pinned="keets://"+drive+path+"?hash="+KeetsRef.sha(bytes);
        KeetsLink.Session session=mock(KeetsLink.Session.class,withSettings().useConstructor(7106,"b".repeat(64),drive));
        when(session.request(eq("POST"),eq("/media"+path),eq(bytes),eq(4096)))
                .thenReturn(new JSONObject().put("url",pinned).put("size",bytes.length).toString().getBytes());
        when(session.request(eq("GET"),anyString(),isNull(),eq(bytes.length))).thenReturn(bytes).thenReturn(new byte[]{4,3,2,1});
        try(MockedStatic<KeetsLink> link=mockStatic(KeetsLink.class)) {
            link.when(KeetsLink::ready).thenReturn(session);
            KeetsHostUploader up=new KeetsHostUploader(Hosting.Profile.fresh(Hosting.TYPE_KEETS));
            assertEquals(pinned,up.putFile(bytes,"owner/media/photo.jpg","image/jpeg"));
            assertThrows(Hosting.HostingException.class,()->up.putFile(bytes,"owner/media/photo.jpg","image/jpeg"));
        }
    }
    @Test public void boundedClientReaderRejectsOversizeAndExpiredReads() throws Exception {
        assertArrayEquals(new byte[]{1,2},KeetsLink.read(new java.io.ByteArrayInputStream(new byte[]{1,2}),2,Long.MAX_VALUE));
        assertThrows(java.io.IOException.class,()->KeetsLink.read(new java.io.ByteArrayInputStream(new byte[]{1,2,3}),2,Long.MAX_VALUE));
        assertThrows(java.io.InterruptedIOException.class,()->KeetsLink.read(new java.io.ByteArrayInputStream(new byte[]{1}),2,0));
    }

    @Test public void privateAttachmentsAndOversizedProfilesAreRejectedBeforeTransport() throws Exception {
        try(MockedStatic<KeetsLink> link=mockStatic(KeetsLink.class)) {
            KeetsHostUploader up=new KeetsHostUploader(Hosting.Profile.fresh(Hosting.TYPE_KEETS));
            for(String path:new String[]{"dm.jpg","../profile.json","chat/photo.jpg","owner/../profile.json"})
                assertThrows(Hosting.HostingException.class,()->up.putFile(new byte[]{1},path,"image/jpeg"));
            byte[] large=new byte[(int)KeetsRef.PROFILE_LIMIT+1];
            assertThrows(Hosting.HostingException.class,()->up.putFile(large,"owner/media/photo.mp4","video/mp4"));
            link.verifyNoInteractions();
        }
    }
    @Test public void profilePublicationChecksTotalBeforeWritingAnything() throws Exception {
        String ref="keets://"+"a".repeat(64)+"/salon/owner/media/25165824-"+"b".repeat(64)+".mp4?hash="+"b".repeat(64);
        byte[] json=new JSONObject().put("gallery",new org.json.JSONArray().put(new JSONObject().put("url",ref))).toString().getBytes();
        try(MockedStatic<KeetsLink> link=mockStatic(KeetsLink.class)) {
            assertThrows(Hosting.HostingException.class,()->new KeetsHostUploader(Hosting.Profile.fresh(Hosting.TYPE_KEETS)).putFile(json,"owner/profile.json","application/json"));
            link.verifyNoInteractions();
        }
    }
}

package com.eurobuddha.salon;

import android.content.Context;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class KeetsReadinessTest {
    private JSONObject stat(String base,String file) throws Exception {
        return new JSONObject().put("status",true).put("exists",true).put("isdir",false)
                .put("size",65).put("base",base).put("canonical",file);
    }
    @Test public void tokenPathUsesNodeCanonicalRootAndVersionInsteadOfGuessing() throws Exception {
        String base="/data/data/example/files";
        assertEquals("1.1/keets/apitoken",KeetsLink.tokenPath(stat(base,base+"/1.1/keets/apitoken")));
        assertEquals("future/keets/apitoken",KeetsLink.tokenPath(stat(base,base+"/future/keets/apitoken")));
        assertEquals("keets/apitoken",KeetsLink.tokenPath(stat(base,base+"/keets/apitoken")));
        for(String file:new String[]{base+"-other/keets/apitoken",base+"/../keets/apitoken",base+"/1.1/keets/keypair","keets/apitoken"})
            assertThrows(IOException.class,()->KeetsLink.tokenPath(stat(base,file)));
        assertThrows(IOException.class,()->KeetsLink.tokenPath(stat(base,base+"/keets/apitoken").put("exists",false)));
    }
    @Test public void readinessFindsVersionedTokenThroughExistingNodeBridge() throws Exception {
        NodeApi api=mock(NodeApi.class);Context context=mock(Context.class);ContentResolver resolver=mock(ContentResolver.class);
        when(context.getApplicationContext()).thenReturn(context);when(context.getPackageName()).thenReturn("com.eurobuddha.salon");
        when(context.getContentResolver()).thenReturn(resolver);
        Uri uri=mock(Uri.class);when(uri.getScheme()).thenReturn("content");
        String drive="a".repeat(64),token="b".repeat(64),base="/data/data/node/files";
        JSONObject info=new JSONObject().put("boundedMedia",true).put("salondrive","0x"+drive).put("apiport",11006);
        doAnswer(i->{ ((NodeApi.Cb)i.getArgument(2)).onResult(new JSONObject().put("status",true).put("response",info));return null; }).when(api).cmd(eq("keets action:info"),eq(30_000L),any());
        doAnswer(i->{ ((NodeApi.Cb)i.getArgument(2)).onResult(new JSONObject().put("status",true).put("response",new JSONObject().put("data","/data/user/0/node/files/1.1")));return null; }).when(api).cmd(eq("status"),eq(30_000L),any());
        doAnswer(i->{ ((NodeApi.Cb)i.getArgument(4)).onResult(stat(base,base+"/1.1/keets/apitoken"));return null; }).when(api).file(eq("stat"),eq("/data/user/0/node/files/1.1/keets/apitoken"),isNull(),isNull(),any());
        doAnswer(i->{ ((NodeApi.Cb)i.getArgument(4)).onResult(new JSONObject().put("status",true).put("uri","content://node/token"));return null; }).when(api).file(eq("get"),eq("1.1/keets/apitoken"),isNull(),isNull(),any());
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream((token+"\n").getBytes(StandardCharsets.US_ASCII)));
        try(MockedStatic<Looper> loopers=mockStatic(Looper.class);MockedStatic<Uri> uris=mockStatic(Uri.class);
            MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->when(h.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;}));
            MockedConstruction<KeetsLink.Session> sessions=mockConstruction(KeetsLink.Session.class,(session,c)->{
                assertEquals(11006,c.arguments().get(0));assertEquals(token,c.arguments().get(1));assertEquals(drive,c.arguments().get(2));
                when(session.request(eq("GET"),eq("/info"),isNull(),eq(4096))).thenReturn(info.toString().getBytes(StandardCharsets.UTF_8));
            })) {
            loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));uris.when(()->Uri.parse("content://node/token")).thenReturn(uri);
            KeetsLink.connect(context,api);
            try { assertNotNull(KeetsLink.ready());assertEquals(1,sessions.constructed().size()); }
            finally { KeetsLink.release(api); }
        }
        verify(api).file(eq("get"),eq("1.1/keets/apitoken"),isNull(),isNull(),any());
        verify(api,never()).file(eq("get"),eq("keets/apitoken"),isNull(),isNull(),any());
    }
}

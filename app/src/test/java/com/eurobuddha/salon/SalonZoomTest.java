package com.eurobuddha.salon;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SalonZoomTest {
    ZoomImageView image;
    @Before public void setup() {
        android.app.Activity activity=Robolectric.buildActivity(android.app.Activity.class).setup().visible().get();
        image=new ZoomImageView(activity);activity.setContentView(image);Shadows.shadowOf(Looper.getMainLooper()).idle();image.layout(0,0,600,600);
        image.setImageDrawable(new BitmapDrawable(image.getResources(),Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();image.fitToView();
    }
    RectF bounds() { RectF r=new RectF(0,0,image.getDrawable().getIntrinsicWidth(),image.getDrawable().getIntrinsicHeight());image.getImageMatrix().mapRect(r);return r; }
    void touch(long time,int action,float...xs) {
        MotionEvent.PointerProperties[] p=new MotionEvent.PointerProperties[xs.length];MotionEvent.PointerCoords[] c=new MotionEvent.PointerCoords[xs.length];
        for(int i=0;i<xs.length;i++) {p[i]=new MotionEvent.PointerProperties();p[i].id=i;p[i].toolType=MotionEvent.TOOL_TYPE_FINGER;c[i]=new MotionEvent.PointerCoords();c[i].x=xs[i];c[i].y=300;c[i].pressure=1;c[i].size=1;}
        MotionEvent e=MotionEvent.obtain(1000,time,action,xs.length,p,c,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        image.dispatchTouchEvent(e);e.recycle();
    }
    @Test public void pinchEnlargesImageAndDoesNotNavigateGallery() {
        AtomicInteger swipes=new AtomicInteger();image.setSwipeListener(next->swipes.incrementAndGet());
        float fit=bounds().width();
        touch(1000,MotionEvent.ACTION_DOWN,230);
        touch(1020,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),230,370);
        for(int i=1;i<=6;i++)touch(1020+i*30,MotionEvent.ACTION_MOVE,230-i*30,370+i*30);
        assertTrue("Pinch must enlarge the rendered image",bounds().width()>fit*1.2f);
        touch(1230,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),50,550);
        touch(1250,MotionEvent.ACTION_UP,50);
        assertEquals(0,swipes.get());
        image.fitToView();assertEquals(fit,bounds().width(),0.1f);
    }
    @Test public void newImageResetsOldZoomAndFitsBothAxes() {
        touch(1000,MotionEvent.ACTION_DOWN,230);touch(1020,MotionEvent.ACTION_POINTER_DOWN|256,230,370);
        for(int i=1;i<=6;i++)touch(1020+i*30,MotionEvent.ACTION_MOVE,230-i*30,370+i*30);
        image.setImageDrawable(new BitmapDrawable(image.getResources(),Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();RectF r=bounds();
        assertEquals(image.getHeight(),r.height(),0.1f);assertEquals(r.height()/2,r.width(),0.1f);assertEquals((image.getWidth()-r.width())/2,r.left,0.1f);
    }
}

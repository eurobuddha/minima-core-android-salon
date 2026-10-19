package com.eurobuddha.salon;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercise real bounded Salon widgets through their full parent touch dispatch. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class SalonScrollTest {
    MainActivity owner;
    Activity window;
    NestedScrollView page;
    SwipeRefreshLayout refresh;
    LinearLayout body;
    long down, time;
    @Before public void setup() throws Exception {
        owner=Robolectric.buildActivity(MainActivity.class).get();
        window=Robolectric.buildActivity(Activity.class).setup().get();
        Field f=MainActivity.class.getDeclaredField("scroll");
        page=(NestedScrollView)f.getType().getConstructor(Context.class).newInstance(window);
        page.setFillViewport(true);
        body=new LinearLayout(window);body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body,new FrameLayout.LayoutParams(-1,-2));
        refresh=new SwipeRefreshLayout(window);refresh.addView(page);
        window.setContentView(refresh);
    }
    @After public void cleanup() throws Exception {
        Field io=MainActivity.class.getDeclaredField("io");io.setAccessible(true);
        ((ExecutorService)io.get(owner)).shutdownNow();
    }
    View bounded(String name,int cap) throws Exception {
        Class<?> cls=Class.forName(MainActivity.class.getName()+"$"+name);
        Constructor<?> c=cls.getDeclaredConstructor(MainActivity.class,Context.class,int.class);
        c.setAccessible(true);return (View)c.newInstance(owner,window,cap);
    }
    void add(View child,int h) { body.addView(child,new LinearLayout.LayoutParams(-1,h)); }
    View content(int h) { View v=new View(window);v.setMinimumHeight(h);return v; }
    void layout(View section) {
        add(content(80),80);add(section,-2);add(content(1000),1000);
        refresh.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
        refresh.layout(0,0,400,600);
    }
    NestedScrollView gallery(int h) throws Exception {
        NestedScrollView inner=(NestedScrollView)bounded("BoundedScroll",300);
        inner.addView(content(h),new FrameLayout.LayoutParams(-1,h));layout(inner);return inner;
    }
    void touch(int action,float x,float y) {
        if(action==MotionEvent.ACTION_DOWN)down=time=SystemClock.uptimeMillis();
        time+=30;
        MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);refresh.dispatchTouchEvent(e);e.recycle();
    }
    @Test public void sameSwipeCrossesGalleryBottomAndContinuesMovingPage() throws Exception {
        NestedScrollView inner=gallery(1200);inner.scrollTo(0,840); // 60 px before bottom
        touch(MotionEvent.ACTION_DOWN,180,290);
        for(int y=270;y>=90;y-=20)touch(MotionEvent.ACTION_MOVE,180,y);
        assertEquals(900,inner.getScrollY());assertTrue("Unconsumed drag must lift page",page.getScrollY()>80);
        int before=page.getScrollY();touch(MotionEvent.ACTION_MOVE,180,70);
        assertEquals("No jump or trapped gesture",20,page.getScrollY()-before);
        touch(MotionEvent.ACTION_CANCEL,180,70);
    }
    @Test public void sameSwipeCrossesGalleryTopAndMovesPageBack() throws Exception {
        NestedScrollView inner=gallery(1200);inner.scrollTo(0,40);page.scrollTo(0,100);
        touch(MotionEvent.ACTION_DOWN,180,120);
        for(int y=140;y<=260;y+=20)touch(MotionEvent.ACTION_MOVE,180,y);
        assertEquals(0,inner.getScrollY());assertTrue(page.getScrollY()<100);
        assertFalse("Scrolling the gallery must not trigger refresh",refresh.isRefreshing());
        touch(MotionEvent.ACTION_CANCEL,180,260);
    }
    @Test public void middleOfGalleryScrollsInternallyAndShortGalleryScrollsPage() throws Exception {
        NestedScrollView inner=gallery(1200);inner.scrollTo(0,300);
        touch(MotionEvent.ACTION_DOWN,180,290);touch(MotionEvent.ACTION_MOVE,180,240);touch(MotionEvent.ACTION_MOVE,180,180);
        assertTrue(inner.getScrollY()>300);assertEquals(0,page.getScrollY());touch(MotionEvent.ACTION_CANCEL,180,180);
        body.removeAllViews();inner=gallery(100);
        assertEquals(100,inner.getHeight());
        touch(MotionEvent.ACTION_DOWN,180,160);touch(MotionEvent.ACTION_MOVE,180,110);touch(MotionEvent.ACTION_MOVE,180,60);
        assertEquals(0,inner.getScrollY());assertTrue(page.getScrollY()>0);touch(MotionEvent.ACTION_CANCEL,180,60);
    }
    @Test public void postsRecyclerPassesBottomDragToPage() throws Exception {
        RecyclerView posts=(RecyclerView)bounded("BoundedRecycler",300);
        posts.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            public int getItemCount(){return 20;}
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p,int type){
                View row=content(100);row.setLayoutParams(new RecyclerView.LayoutParams(-1,100));return new RecyclerView.ViewHolder(row){};
            }
            public void onBindViewHolder(RecyclerView.ViewHolder h,int pos){}
        });
        layout(posts);posts.scrollBy(0,10000);assertFalse(posts.canScrollVertically(1));
        touch(MotionEvent.ACTION_DOWN,180,290);touch(MotionEvent.ACTION_MOVE,180,230);touch(MotionEvent.ACTION_MOVE,180,150);
        assertTrue(page.getScrollY()>0);touch(MotionEvent.ACTION_CANCEL,180,150);
    }
    @Test public void horizontalVideoSwipeAndTapRemainAvailable() throws Exception {
        NestedScrollView inner=(NestedScrollView)bounded("BoundedScroll",300);
        HorizontalScrollView videos=new HorizontalScrollView(window);
        TextView card=new TextView(window);card.setText("Video");card.setMinWidth(1000);AtomicInteger taps=new AtomicInteger();card.setOnClickListener(v->taps.incrementAndGet());
        videos.addView(card,new FrameLayout.LayoutParams(1000,200));inner.addView(videos,new FrameLayout.LayoutParams(-1,200));layout(inner);
        touch(MotionEvent.ACTION_DOWN,320,160);touch(MotionEvent.ACTION_MOVE,240,160);touch(MotionEvent.ACTION_MOVE,120,160);touch(MotionEvent.ACTION_CANCEL,120,160);
        assertTrue("horizontal scroll="+videos.getScrollX()+" width="+videos.getWidth()+" content="+card.getWidth(),videos.getScrollX()>0);assertEquals(0,page.getScrollY());assertEquals(0,inner.getScrollY());assertEquals(0,taps.get());
        touch(MotionEvent.ACTION_DOWN,180,160);touch(MotionEvent.ACTION_UP,180,160);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,taps.get());
    }
}

#!/usr/bin/env python3
"""Offline regression smoke tests for the real Java log/status/tile sources.

Requires Python 3 and JDK 17+. Tiny Android/AIDL substitutes live only in a temp
folder. This checks Java compilation and deterministic logic; it is NOT an
Android build, resource linker, Binder integration test, or emulator test.
"""
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    'main/src/main/java/de/blinkt/openvpn/core/' + name + '.java'
    for name in ['VpnStatus', 'LogItem', 'LogFileHandler', 'ConnectionStatus',
                 'TrafficHistory', 'OpenVPNStatusService', 'StatusListener']
] + ['main/src/ui/java/de/blinkt/openvpn/OpenVPNTileService.java']
STUBS = {
'android.os.Parcelable': '''public interface Parcelable {
 int describeContents(); void writeToParcel(Parcel p,int f);
 interface Creator<T>{T createFromParcel(Parcel p);T[] newArray(int n);}
}''',
'android.os.Parcel': '''public class Parcel {
 public void writeInt(int x){} public void writeLong(long x){} public void writeString(String s){}
 public void writeArray(Object[] a){} public void writeList(java.util.List a){}
 public int readInt(){return 0;} public long readLong(){return 0;} public String readString(){return null;}
 public Object[] readArray(ClassLoader c){return null;} public void readList(java.util.List l,ClassLoader c){}
}''',
'android.os.Build': '''public class Build {
 public static String MODEL="test", BOARD="", BRAND="", ID="", FINGERPRINT="";
 public static class VERSION {public static int SDK_INT=34; public static String RELEASE="";}
 public static class VERSION_CODES {public static final int N=24,Q=29,R=30,UPSIDE_DOWN_CAKE=34;}
}''',
'android.os.Looper': '''public class Looper { private static final Looper MAIN=new Looper();
 public static Looper getMainLooper(){return MAIN;} }''',
'android.os.Message': '''public class Message {
 public int what;public Object obj; public Handler target;
 public void sendToTarget(){target.sendMessage(this);}
}''',
'android.os.Handler': '''public class Handler {
 private final Looper looper;
 private static class Task {Handler h;Runnable r;Message m; Task(Handler h,Runnable r,Message m){this.h=h;this.r=r;this.m=m;}}
 private static final java.util.List<Task> queue=new java.util.ArrayList<>();
 public Handler(){this(Looper.getMainLooper());} public Handler(Looper l){looper=l;}
 public Message obtainMessage(int w){return obtainMessage(w,null);}
 public Message obtainMessage(int w,Object o){Message m=new Message();m.what=w;m.obj=o;m.target=this;return m;}
 public boolean sendMessage(Message m){if(looper!=Looper.getMainLooper())handleMessage(m);else queue.add(new Task(this,null,m));return true;}
 public boolean sendEmptyMessage(int w){return sendMessage(obtainMessage(w));}
 public boolean post(Runnable r){queue.add(new Task(this,r,null));return true;}
 public boolean postDelayed(Runnable r,long delay){return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(t->t.h==this&&t.r==r);}
 public void removeCallbacksAndMessages(Object o){queue.removeIf(t->t.h==this);}
 public void handleMessage(Message m){}
 public static void drain(){int limit=10000;while(!queue.isEmpty()) {if(--limit==0)throw new AssertionError("Unbounded handler queue");Task t=queue.remove(0);if(t.r!=null)t.r.run();else t.h.handleMessage(t.m);}}
}''',
'android.os.HandlerThread': '''public class HandlerThread {
 public HandlerThread(String s,int p){} public void start(){} public Looper getLooper(){return new Looper();}
}''',
'android.os.IBinder': '''public interface IBinder {default Object queryLocalInterface(String s){return null;}}''',
'android.os.RemoteException': '''public class RemoteException extends Exception {public RemoteException(){} public RemoteException(String s){super(s);}}''',
'android.os.SystemClock': '''public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}''',
'android.os.Bundle': '''public class Bundle {}''',
'android.os.RemoteCallbackList': '''public class RemoteCallbackList<T> {
 private java.util.LinkedHashMap<T,Object> entries=new java.util.LinkedHashMap<>();
 private java.util.List<java.util.Map.Entry<T,Object>> snapshot;
 public synchronized boolean register(T t,Object c){entries.put(t,c);return true;}
 public synchronized boolean unregister(T t){return entries.remove(t)!=null;}
 public synchronized int beginBroadcast(){snapshot=new java.util.ArrayList<>(entries.entrySet());return snapshot.size();}
 public T getBroadcastItem(int i){return snapshot.get(i).getKey();} public Object getBroadcastCookie(int i){return snapshot.get(i).getValue();}
 public void finishBroadcast(){snapshot=null;} public void kill(){entries.clear();}
}''',
'android.os.ParcelFileDescriptor': '''public class ParcelFileDescriptor implements java.io.Closeable {
 java.io.InputStream in;java.io.OutputStream out;
 public static ParcelFileDescriptor[] createPipe() throws java.io.IOException {java.io.PipedInputStream i=new java.io.PipedInputStream(65536);java.io.PipedOutputStream o=new java.io.PipedOutputStream(i);ParcelFileDescriptor a=new ParcelFileDescriptor(),b=new ParcelFileDescriptor();a.in=i;b.out=o;return new ParcelFileDescriptor[]{a,b};}
 public void close()throws java.io.IOException {if(in!=null)in.close();if(out!=null)out.close();}
 public static class AutoCloseInputStream extends java.io.FilterInputStream {public AutoCloseInputStream(ParcelFileDescriptor p){super(p.in);}}
 public static class AutoCloseOutputStream extends java.io.FilterOutputStream {public AutoCloseOutputStream(ParcelFileDescriptor p){super(p.out);}}
}''',
'android.content.Context': '''public class Context {
 public static final int BIND_AUTO_CREATE=1;public static final String ACTIVITY_SERVICE="activity";
 public String getString(int r,Object... args){return "res-"+r+(args.length>0?" "+java.util.Arrays.toString(args):"");}
 public String getPackageName(){return "de.blinkt.openvpn";}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public java.io.File getCacheDir(){return new java.io.File(System.getProperty("java.io.tmpdir"),"openvpn-smoke-unused");}
 public boolean bindService(Intent i,ServiceConnection c,int flags){return true;}
 public void unbindService(ServiceConnection c){} public Context getBaseContext(){return this;}
 public Object getSystemService(String s){return new android.app.ActivityManager();}
}''',
'android.content.Intent': '''public class Intent {
 public static final String ACTION_MAIN="main";public static final int FLAG_ACTIVITY_NEW_TASK=1;
 String action;Object data;ComponentName component;
 public Intent(){} public Intent(Context c,Class<?> cls){} public Intent(String a,Object d){action=a;data=d;}
 public Intent setAction(String a){action=a;return this;} public String getAction(){return action;}
 public Object getData(){return data;} public ComponentName getComponent(){return component;}
 public Intent setComponent(ComponentName c){component=c;return this;} public android.os.Bundle getExtras(){return null;}
 public Intent putExtras(android.os.Bundle b){return this;} public Intent addFlags(int f){return this;}
 public Intent putExtra(String s,String v){return this;}public Intent putExtra(String s,boolean v){return this;}
}''',
'android.content.ComponentName': '''public class ComponentName {public String getPackageName(){return "de.blinkt.openvpn";}}''',
'android.content.ServiceConnection': '''public interface ServiceConnection {
 void onServiceConnected(ComponentName n,android.os.IBinder b);void onServiceDisconnected(ComponentName n);
 default void onBindingDied(ComponentName n){} default void onNullBinding(ComponentName n){}
}''',
'android.content.pm.Signature': '''public class Signature {public byte[] toByteArray(){return new byte[0];}}''',
'android.content.pm.PackageInfo': '''public class PackageInfo {public Signature[] signatures={new Signature()};public String versionName="test";}''',
'android.content.pm.PackageManager': '''public class PackageManager {
 public static final int GET_SIGNATURES=1; public static class NameNotFoundException extends Exception{}
 public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{return new PackageInfo();}
}''',
'android.content.res.Resources': '''public class Resources {public static class NotFoundException extends RuntimeException{}}''',
'android.text.TextUtils': '''public class TextUtils {public static String join(CharSequence d,Iterable<?> v){return v.toString();}}''',
'android.util.Log': '''public class Log {
 public static int e(String t,String s,Throwable e){return 0;}public static int w(String t,String s,Throwable e){return 0;}
 public static int e(String t,String s){return 0;}public static int w(String t,String s){return 0;}
 public static int i(String t,String s){return 0;}public static int d(String t,String s){return 0;}public static int v(String t,String s){return 0;}
}''',
'android.util.Pair': '''public class Pair<A,B> {public A first;public B second;public static <A,B> Pair<A,B> create(A a,B b){Pair<A,B> p=new Pair<>();p.first=a;p.second=b;return p;}}''',
'android.app.Service': '''public class Service extends android.content.Context {
 public void onCreate(){}public void onDestroy(){}public android.os.IBinder onBind(android.content.Intent i){return null;}
}''',
'android.app.ActivityManager': '''public class ActivityManager {public java.util.List<ApplicationExitInfo> getHistoricalProcessExitReasons(String p,int i,int n){return java.util.Collections.emptyList();}}''',
'android.app.ApplicationExitInfo': '''public class ApplicationExitInfo {public long getTimestamp(){return 0;}public String getProcessName(){return "test";}}''',
'android.app.PendingIntent': '''public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;
 public static PendingIntent getActivity(android.content.Context c,int id,android.content.Intent i,int f){return new PendingIntent();}}
''',
'android.service.quicksettings.Tile': '''public class Tile {
 public static final int STATE_UNAVAILABLE=0,STATE_INACTIVE=1,STATE_ACTIVE=2;
 public int state,updates; public CharSequence label,subtitle;
 public void setState(int s){state=s;} public void setLabel(CharSequence s){label=s;}public CharSequence getLabel(){return label;}
 public void setSubtitle(CharSequence s){subtitle=s;}public void setStateDescription(CharSequence s){}
 public void setContentDescription(CharSequence s){}public void updateTile(){updates++;}
}''',
'android.service.quicksettings.TileService': '''public class TileService extends android.app.Service {
 private final Tile tile=new Tile();public Tile getQsTile(){return tile;}public boolean isLocked(){return false;}
 public void unlockAndRun(Runnable r){r.run();}public void onClick(){} public void onStartListening(){}public void onStopListening(){}
 public void startActivityAndCollapse(android.content.Intent i){}public void startActivityAndCollapse(android.app.PendingIntent i){}
}''',
'android.widget.Toast': '''public class Toast {public static final int LENGTH_SHORT=0;public static Toast makeText(android.content.Context c,int r,int len){return new Toast();}public void show(){}}''',
'de.blinkt.openvpn.BuildConfig': '''public class BuildConfig {public static final boolean DEBUG=false;public static final String FLAVOR="ui";}''',
'de.blinkt.openvpn.LaunchVPN': '''public class LaunchVPN {public static final String EXTRA_KEY="key";}''',
'de.blinkt.openvpn.VpnProfile': '''public class VpnProfile {String name,id;
 public VpnProfile(String id,String name){this.id=id;this.name=name;}public String getName(){return name;}public String getUUIDString(){return id;}}
''',
'de.blinkt.openvpn.core.NativeUtils': '''public class NativeUtils {public static String getNativeAPI(){return "test";}}''',
'de.blinkt.openvpn.core.OpenVPNManagement': '''public class OpenVPNManagement {public enum pauseReason{noNetwork,screenOff,userPause}}''',
'de.blinkt.openvpn.core.OpenVPNService': '''public class OpenVPNService {public static final String START_SERVICE="start",EXTRA_START_REASON="reason";}''',
'de.blinkt.openvpn.core.PasswordCache': '''public class PasswordCache {public static void setCachedPassword(String s,int t,String p){}}''',
'de.blinkt.openvpn.core.ProfileManager': '''public class ProfileManager {
 public static de.blinkt.openvpn.VpnProfile defaultProfile,currentProfile;
 public static de.blinkt.openvpn.VpnProfile getAlwaysOnVPN(android.content.Context c){return defaultProfile;}
 public static de.blinkt.openvpn.VpnProfile get(android.content.Context c,String id){return currentProfile;}
 public static void notifyProfileVersionChanged(android.content.Context c,String s,int i){}
}''',
'de.blinkt.openvpn.core.IOpenVPNServiceInternal': '''public interface IOpenVPNServiceInternal {
 void stopVPN(boolean b)throws android.os.RemoteException;
 abstract class Stub {public static IOpenVPNServiceInternal asInterface(android.os.IBinder b){return null;}}
}''',
'de.blinkt.openvpn.core.IStatusCallbacks': '''public interface IStatusCallbacks {
 void newLogItem(LogItem l)throws android.os.RemoteException;void logBufferCleared()throws android.os.RemoteException;
 void updateStateString(String s,String m,int r,ConnectionStatus l,android.content.Intent i)throws android.os.RemoteException;
 void updateByteCount(long i,long o)throws android.os.RemoteException;void connectedVPN(String s)throws android.os.RemoteException;
 void notifyProfileVersionChanged(String s,int v)throws android.os.RemoteException;
 abstract class Stub implements IStatusCallbacks,android.os.IBinder {}
}''',
'de.blinkt.openvpn.core.IServiceStatus': '''public interface IServiceStatus {
 android.os.ParcelFileDescriptor registerStatusCallback(IStatusCallbacks cb)throws android.os.RemoteException;
 void unregisterStatusCallback(IStatusCallbacks cb)throws android.os.RemoteException;void clearLog()throws android.os.RemoteException;
 String getLastConnectedVPN()throws android.os.RemoteException;void setCachedPassword(String s,int i,String p)throws android.os.RemoteException;
 TrafficHistory getTrafficHistory()throws android.os.RemoteException;void notifyProfileVersionChanged(String s,int v)throws android.os.RemoteException;
 abstract class Stub implements IServiceStatus,android.os.IBinder {public static IServiceStatus asInterface(android.os.IBinder b){return (IServiceStatus)b;}}
}''',
}
for name in ['androidx.annotation.StringRes','androidx.annotation.Nullable','androidx.annotation.NonNull']:
    STUBS[name]='public @interface '+name.split('.')[-1]+' {}'
for name in ['android.annotation.TargetApi','androidx.annotation.RequiresApi']:
    STUBS[name]='public @interface '+name.split('.')[-1]+' {int value();}'
STUBS['android.annotation.SuppressLint']='public @interface SuppressLint {String[] value();}'

RUNNER = r'''
package de.blinkt.openvpn.core;
import android.os.*;
import android.service.quicksettings.Tile;
import de.blinkt.openvpn.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public class RegressionRunner {
 static int checks;
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
 static LogItem item(String s,long time){return new LogItem(VpnStatus.LogLevel.INFO,s,time);}
 static void sorted(){long previous=Long.MIN_VALUE;for(LogItem i:VpnStatus.getlogbuffer()){check(i.getLogtime()>=previous,"chronological log order");previous=i.getLogtime();}}
 static class Cache extends LogFileHandler {
   Cache(){super(new Looper());}void output(OutputStream s){mLogFile=s;}
   void read(InputStream s)throws IOException{readCacheContents(s);}
 }
 static class Callback extends IStatusCallbacks.Stub {
   List<LogItem> live=new ArrayList<>();List<String> states=new ArrayList<>();String profile;int clears;
   public void newLogItem(LogItem i){live.add(i);}public void logBufferCleared(){clears++;}
   public void updateStateString(String s,String m,int r,ConnectionStatus l,android.content.Intent i){states.add(s);}
   public void updateByteCount(long i,long o){}public void connectedVPN(String s){profile=s;}
   public void notifyProfileVersionChanged(String s,int v){}
 }
 static class TestTile extends OpenVPNTileService {
   int binds,lastFlags,unbinds;boolean nullTile;
   public Tile getQsTile(){return nullTile?null:super.getQsTile();}
   public boolean bindService(android.content.Intent i,android.content.ServiceConnection c,int flags){binds++;lastFlags=flags;return true;}
   public void unbindService(android.content.ServiceConnection c){unbinds++;}
 }
 static void state(ConnectionStatus s){VpnStatus.updateStateString(s.name(),"",R.string.state_connected,s);Handler.drain();}
 static void logTests()throws Exception {
   VpnStatus.clearLog();
   VpnStatus.newLogItem(item("live",300),false);
   VpnStatus.newLogItem(item("cached 1",100),true);
   VpnStatus.newLogItem(item("cached 2",200),true);sorted();
   int[] calls={0};VpnStatus.LogListener listener=i->calls[0]++;
   VpnStatus.addLogListener(listener);
   LogItem newest=item("newest",System.currentTimeMillis()+100000);
   VpnStatus.newLogItemIfUnique(newest);int size=VpnStatus.getlogbuffer().length;
   VpnStatus.newLogItemIfUnique(newest);
   check(VpnStatus.getlogbuffer().length==size,"newest duplicate not stored");check(calls[0]==1,"duplicate not broadcast");
   VpnStatus.newLogItemIfUnique(item("another message",newest.getLogtime()));
   check(calls[0]==2,"different messages at same timestamp preserved");
   VpnStatus.removeLogListener(listener);
   check(!newest.equals(null)&&!newest.equals("x"),"equals null/unrelated safe");
   LogItem resource=new LogItem(VpnStatus.LogLevel.INFO,123);
   check(!resource.equals(newest)&&!newest.equals(resource),"resource/text equality safe");
   byte[] data=newest.getMarshaledBytes();LogItem copy=new LogItem(data,data.length);
   check(copy.equals(newest)&&copy.hashCode()==newest.hashCode(),"round trip equality");
   int[] resets={0};VpnStatus.LogListener reset=new VpnStatus.LogListener(){public void newLog(LogItem i){}public void logBufferChanged(){resets[0]++;}};
   VpnStatus.addLogListener(reset);VpnStatus.clearLog();check(resets[0]==1,"clear notifies adapters");VpnStatus.removeLogListener(reset);
   Cache cache=new Cache();ByteArrayOutputStream bytes=new ByteArrayOutputStream();cache.output(bytes);
   cache.writeEscapedBytes(item("U and V escaped",1000).getMarshaledBytes());
   cache.writeEscapedBytes(item("next",2000).getMarshaledBytes());
   byte[] encoded=bytes.toByteArray();
   cache.read(new FilterInputStream(new ByteArrayInputStream(encoded)){public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(n,1));}});
   sorted();cache.read(new ByteArrayInputStream(encoded));
   check(Arrays.stream(VpnStatus.getlogbuffer()).filter(i->i.getLogtime()==1000||i.getLogtime()==2000).count()==2,"cached replay is unique");
   byte[] badLength=ByteBuffer.allocate(5).put((byte)0x55).putInt(20000).array();
   try{cache.read(new ByteArrayInputStream(badLength));throw new AssertionError("invalid length accepted");}catch(IOException expected){checks++;}
   byte[] truncated=Arrays.copyOf(encoded,encoded.length-1);
   try{cache.read(new ByteArrayInputStream(truncated));throw new AssertionError("truncation accepted");}catch(EOFException expected){checks++;}
   int[] fresh={0};VpnStatus.LogListener atomic=i->fresh[0]++;
   LogItem[] snapshot=VpnStatus.addLogListenerAndGetBuffer(atomic);
   VpnStatus.newLogItem(item("after subscribe",3000));check(fresh[0]==1&&snapshot.length>0,"snapshot plus live subscription");VpnStatus.removeLogListener(atomic);
   System.out.println("PASS log ordering, deduplication, equality, clear, cache framing, subscription");
 }
 static void tileTests()throws Exception {
   TestTile tile=new TestTile();ProfileManager.defaultProfile=new VpnProfile("default","Default");
   VpnStatus.setStatusAvailable(false);tile.onStartListening();Handler.drain();
   check(tile.getQsTile().state==Tile.STATE_UNAVAILABLE,"cold start waits for authoritative state");
   tile.onClick();check(tile.binds==0,"no action on unsynchronized tile");
   VpnStatus.setStatusAvailable(true);
   for(ConnectionStatus s:ConnectionStatus.values()){
     state(s);check(tile.getQsTile().state==(s==ConnectionStatus.LEVEL_CONNECTED?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE),"tile mapping "+s);
   }
   ProfileManager.defaultProfile=null;state(ConnectionStatus.LEVEL_NOTCONNECTED);
   check(tile.getQsTile().state==Tile.STATE_UNAVAILABLE,"no default disconnected is unavailable");
   ProfileManager.currentProfile=new VpnProfile("current","Current");VpnStatus.setConnectedVPNProfile("current");state(ConnectionStatus.LEVEL_CONNECTED);
   check(tile.getQsTile().state==Tile.STATE_ACTIVE,"connected without default still active");
   tile.onClick();tile.onClick();check(tile.binds==1&&tile.lastFlags==0,"single stop bind without creating service/default profile");
   ProfileManager.currentProfile=new VpnProfile("changed","Changed");VpnStatus.setConnectedVPNProfile("changed");Handler.drain();
   check(tile.getQsTile().label.toString().contains("Changed"),"UUID-only update refreshes label");
   int updates=tile.getQsTile().updates;tile.onStopListening();state(ConnectionStatus.LEVEL_NOTCONNECTED);
   check(tile.getQsTile().updates==updates,"no updates after stopListening");
   tile.nullTile=true;tile.onStartListening();Handler.drain();tile.onDestroy();check(tile.unbinds==1,"pending stop binding released on destroy");
   VpnStatus.setStatusAvailable(false);check(!VpnStatus.isVPNActive()&&VpnStatus.getLastConnectedVPNProfile()==null,"service loss invalidates stale state");
   System.out.println("PASS all tile states, cold start, missing default, repeat click, profile change, lifecycle");
 }
 static void callbackTests()throws Exception {
   StatusListener listener=new StatusListener();
   Class<?> cls=Class.forName("de.blinkt.openvpn.core.StatusListener$StatusCallback");
   java.lang.reflect.Constructor<?> ctor=cls.getDeclaredConstructor(StatusListener.class);ctor.setAccessible(true);
   IStatusCallbacks old=(IStatusCallbacks)ctor.newInstance(listener),current=(IStatusCallbacks)ctor.newInstance(listener);
   java.lang.reflect.Field field=StatusListener.class.getDeclaredField("mCallback");field.setAccessible(true);field.set(listener,current);
   current.connectedVPN("current");current.updateStateString("CONNECTED","",R.string.state_connected,ConnectionStatus.LEVEL_CONNECTED,null);
   old.connectedVPN("old");old.updateStateString("DISCONNECTED","",R.string.state_disconnected,ConnectionStatus.LEVEL_NOTCONNECTED,null);
   check(VpnStatus.isStatusAvailable()&&VpnStatus.isVPNActive()&&"current".equals(VpnStatus.getLastConnectedVPNProfile()),"stale binding callbacks ignored");
   int count=VpnStatus.getlogbuffer().length;old.newLogItem(item("stale callback",1));check(count==VpnStatus.getlogbuffer().length,"stale log callback ignored");
   current.logBufferCleared();java.lang.reflect.Field discard=cls.getDeclaredField("discardSnapshot");discard.setAccessible(true);
   check(discard.getBoolean(current),"clear prevents in-flight snapshot resurrection");
   java.lang.reflect.Field connection=StatusListener.class.getDeclaredField("mConnection");connection.setAccessible(true);
   ((android.content.ServiceConnection)connection.get(listener)).onServiceDisconnected(null);
   check(!VpnStatus.isStatusAvailable()&&!VpnStatus.isVPNActive(),"connection loss clears readiness");
   current.updateStateString("CONNECTED","",R.string.state_connected,ConnectionStatus.LEVEL_CONNECTED,null);
   check(!VpnStatus.isVPNActive(),"late connected event after process loss ignored");
   System.out.println("PASS stale callback isolation, snapshot clear, service disconnect");
 }
 static void serviceTests()throws Exception {
   File directory=java.nio.file.Files.createTempDirectory("vpn-status-cache").toFile();
   OpenVPNStatusService service=new OpenVPNStatusService(){public File getCacheDir(){return directory;}};
   service.onCreate();
   VpnStatus.setConnectedVPNProfile("vpn-a");
   VpnStatus.updateStateString("CONNECTING","",R.string.state_connected,ConnectionStatus.LEVEL_START);
   VpnStatus.updateStateString("CONNECTED","",R.string.state_connected,ConnectionStatus.LEVEL_CONNECTED);
   VpnStatus.newLogItem(item("before-snapshot",10000));
   IServiceStatus binder=(IServiceStatus)service.onBind(null);Callback cb=new Callback();
   ParcelFileDescriptor pipe=binder.registerStatusCallback(cb);
   List<LogItem> history=new ArrayList<>();try(DataInputStream in=new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(pipe))){int n;while((n=in.readUnsignedShort())!=0x7fff){byte[] b=new byte[n];in.readFully(b);history.add(new LogItem(b,n));}}
   VpnStatus.newLogItem(item("after-snapshot",11000));Handler.drain();
   check(cb.states.equals(Collections.singletonList("CONNECTED")),"queued stale states do not overwrite snapshot");
   check("vpn-a".equals(cb.profile),"profile is in initial snapshot");
   check(cb.live.size()==1&&cb.live.get(0).getString(null).equals("after-snapshot"),"snapshot/live boundary has no duplication");
   check(history.stream().anyMatch(i->i.getString(null).equals("before-snapshot")),"history includes pre-registration log");
   binder.clearLog();Handler.drain();check(cb.clears==1,"clear propagates across status bridge");
   binder.unregisterStatusCallback(cb);service.onDestroy();Handler.drain();
   System.out.println("PASS snapshot/live handoff, stale event suppression, profile snapshot, clear propagation");
 }
 public static void main(String[] args)throws Exception {logTests();tileTests();callbackTests();serviceTests();System.out.println("PASS "+checks+" assertions (Android substitutes; device validation still required)");}
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix='openvpn-regression-') as folder:
        temp=Path(folder)
        def write(name, body, package=True):
            p=temp/'src'/Path(name.replace('.', '/')+'.java')
            p.parent.mkdir(parents=True,exist_ok=True)
            prefix='package '+name.rsplit('.',1)[0]+';\n' if package else ''
            p.write_text(prefix+textwrap.dedent(body))
        for name,body in STUBS.items(): write(name,body)
        strings=set()
        for path in SOURCES:
            source=ROOT/path
            strings.update(re.findall(r'R\.string\.(\w+)',source.read_text()))
            target=temp/'src'/Path(*path.split('/java/',1)[1].split('/'))
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(source.read_text())
        fields=''.join('public static final int '+name+'='+str(i+1)+';' for i,name in enumerate(sorted(strings)))
        write('de.blinkt.openvpn.R','public class R {public static class string {'+fields+'}}')
        write('de.blinkt.openvpn.core.RegressionRunner',RUNNER,False)
        java_files=sorted(str(p) for p in (temp/'src').rglob('*.java'))
        subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-Xlint:unchecked','-d',str(temp/'classes'),*java_files],check=True)
        subprocess.run(['java','-cp',str(temp/'classes'),'de.blinkt.openvpn.core.RegressionRunner'],check=True,timeout=30)
if __name__=='__main__': main()

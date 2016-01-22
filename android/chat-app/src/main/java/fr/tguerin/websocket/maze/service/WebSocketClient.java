package fr.tguerin.websocket.maze.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.greenrobot.event.EventBus;
import fr.tguerin.websocket.maze.event.ConnectEvent;
import fr.tguerin.websocket.maze.event.DisconnectEvent;
import fr.tguerin.websocket.maze.event.MessageReceivedEvent;
import fr.tguerin.websocket.maze.event.SendMessageEvent;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.ws.WebSocket;
import okhttp3.ws.WebSocketCall;
import okhttp3.ws.WebSocketListener;
import okio.Buffer;
import timber.log.Timber;

public class WebSocketClient extends Service implements WebSocketListener {

    private static final int CONNECT_TO_WEB_SOCKET = 1;
    private static final int SEND_MESSAGE          = 2;
    private static final int CLOSE_WEB_SOCKET      = 3;
    private static final int DISCONNECT_LOOPER     = 4;

    private static final String KEY_MESSAGE = "keyMessage";

    private Handler       mServiceHandler;
    private Looper        mServiceLooper;
    private WebSocket     mWebSocket;
    //    private boolean mConnected;
    private AtomicBoolean isConnected;

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CONNECT_TO_WEB_SOCKET:
                    connectToWebSocket();
                    break;
                case SEND_MESSAGE:
                    sendMessageThroughWebSocket(msg.getData().getString(KEY_MESSAGE));
                    break;
                case CLOSE_WEB_SOCKET:
                    closeWebSocket();
                    break;
                case DISCONNECT_LOOPER:
                    mServiceLooper.quit();
                    break;
            }
        }
    }

    private void sendMessageThroughWebSocket(String message) {
//        if (!mConnected) {
        if (!isConnected.get()) {
            return;
        }
        try {
            mWebSocket.sendMessage(RequestBody.create(WebSocket.TEXT, message));
        } catch (IOException e) {
            Timber.d("Error sending message", e);
        }
    }

    private void connectToWebSocket() {
        OkHttpClient okHttpClient = new OkHttpClient();
        Request request = new Request.Builder()
                .url("ws://192.168.56.1:5000")
//                .url("ws://10.0.38.3:1999")
                .build();
        WebSocketCall call = WebSocketCall.create(okHttpClient, request);
        call.enqueue(this);

        /*
        try {
            Response response = mWebSocket.connect(WebSocketClient.this);
            if (response.code() == 101) {
                mConnected = true;
            } else {
                Timber.d("Couldn't connect to WebSocket %s %s %s", response.code(), response.message(), response.body() != null ? response.body().string() : "null");
            }

        } catch (IOException e) {
            Timber.d("Couldn't connect to WebSocket", e);
        }
        */
    }

    private void closeWebSocket() {
//        if (!mConnected) {
        if (!isConnected.get()) {
            return;
        }
        try {
            mWebSocket.close(1000, "Goodbye, World!");
        } catch (IOException e) {
            Timber.d("Failed to close WebSocket", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("WebSocket service");
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);
        isConnected = new AtomicBoolean();

        mServiceHandler.sendEmptyMessage(CONNECT_TO_WEB_SOCKET);

        EventBus.getDefault().register(this);
    }

    public void onEvent(SendMessageEvent sendMessageEvent) {
//        if (!mWebSocket.isClosed()) {
//        if (mConnected) {
//        TODO use syncronize
        if (isConnected.get()) {
            Message message = Message.obtain();
            message.what = SEND_MESSAGE;
            Bundle data = new Bundle();
            data.putString(KEY_MESSAGE, sendMessageEvent.message);
            message.setData(data);
            mServiceHandler.sendMessage(message);
        }
    }

    public void onEvent(ConnectEvent event) {
//        if (mWebSocket.isClosed()) {
//        if (!mConnected) {
        if (!isConnected.get()) {
            mServiceHandler.obtainMessage(CONNECT_TO_WEB_SOCKET)
                    .sendToTarget();
        } else {
            Timber.d("WebSocket is already connected");
        }
    }

    public void onEvent(DisconnectEvent event) {
//        if (!mWebSocket.isClosed()) {
//        if (mConnected) {
        if (isConnected.get()) {
            mServiceHandler.obtainMessage(CLOSE_WEB_SOCKET)
                    .sendToTarget();
/*                Message message = Message.obtain();
                message.what = CLOSE_WEB_SOCKET;
                mServiceHandler.sendMessage(message);*/
        } else {
            Timber.d("WebSocket is already disconnected");
        }
    }


    @Override
    public void onDestroy() {
        mServiceHandler.sendEmptyMessage(CLOSE_WEB_SOCKET);
        mServiceHandler.sendEmptyMessage(DISCONNECT_LOOPER);
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        mWebSocket = webSocket;
        Timber.d("onOpen: %s", response);
        if (response.code() == 101) {
//            mConnected = true;
            isConnected.compareAndSet(false, true);
        }

        mServiceHandler.postDelayed(
                new Runnable() {
                    public void run() {
                        try {
                            if (isConnected.get()) {
                                mWebSocket.sendPing(new Buffer());
                                mWebSocket.sendMessage(RequestBody.create(WebSocket.TEXT, "Hello there!"));
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }, TimeUnit.SECONDS.toMillis(5));
    }

    @Override
    public void onFailure(IOException e, Response response) {
//        mConnected = false;
        isConnected.compareAndSet(true, false);
        Timber.d("onFailure: %s", response);
        Timber.e(Log.getStackTraceString(e));
    }

    @Override
    public void onMessage(ResponseBody message) throws IOException {
        if (message.contentType() == WebSocket.TEXT) {
            EventBus.getDefault().post(new MessageReceivedEvent(message.source().readUtf8()));
            message.close();
        }
    }

    @Override
    public void onPong(Buffer payload) {
        Timber.d("onPong: %s", payload);
        if (payload != null) payload.close();

        mServiceHandler.postDelayed(
                new Runnable() {
                    public void run() {
                        try {
                            if (isConnected.get()) {
                                mWebSocket.sendPing(new Buffer());
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }, TimeUnit.SECONDS.toMillis(9));
    }

    @Override
    public void onClose(int code, String reason) {
//        mConnected = false;
        isConnected.compareAndSet(true, false);
        Timber.d("Websocket is closed %s %s", code, reason);
    }

}

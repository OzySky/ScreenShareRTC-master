/*
 * Copyright 2014 Pierre Chabardes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.pchab.androidrtc;

import android.util.Log;


import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.voiceengine.WebRtcAudioRecord;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedList;


import android.content.Context;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;


public class WebRtcClient {

    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private final static String TAG = "RtcActivity";
    private final static int MAX_PEER = 2;
    private boolean[] endPoints = new boolean[MAX_PEER];
    private PeerConnectionFactory factory;
    private HashMap<String, Peer> peers = new HashMap<>();
    private LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
    private PeerConnectionClient.PeerConnectionParameters mPeerConnParams;
    private MediaConstraints mPeerConnConstraints = new MediaConstraints();
    private MediaStream mLocalMediaStream;
    private VideoSource mVideoSource;
    private RtcListener mListener;
    private Socket mSocket;
    VideoCapturer videoCapturer;
    MessageHandler messageHandler = new MessageHandler();
    Context mContext;

    /**
     * Implement this interface to be notified of events.
     */
    public interface RtcListener {
        void onReady(String remoteId);

        void onCall(String applicant);

        void onHandup();

        void onStatusChanged(String newStatus);
    }

    public interface Command {
        void execute(String peerId, JSONObject payload) throws JSONException;
    }

    public class CreateOfferCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, " WebRtcClient CreateOfferCommand");
            Peer peer = peers.get(peerId);
            peer.pc.createOffer(peer, mPeerConnConstraints);
//            sendMessage("r0Z049NKJF2ZCIhRAAAZ","offer",new JSONObject());
        }
    }

    public class CreateAnswerCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "WebRtcClient CreateAnswerCommand");
            Peer peer = peers.get(peerId);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.optString("type")),
                    payload.optString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
            peer.pc.createAnswer(peer, mPeerConnConstraints);
        }
    }

    public class SetRemoteSDPCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "WebRtcClient SetRemoteSDPCommand");
            Peer peer = peers.get(peerId);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.optString("type")),
                    payload.optString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
        }
    }

    public class AddIceCandidateCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "WebRtcClient AddIceCandidateCommand");
            PeerConnection pc = peers.get(peerId).pc;
            if (pc.getRemoteDescription() != null) {
                IceCandidate candidate = new IceCandidate(
                        payload.optString("id"),
                        payload.optInt("label"),
                        payload.optString("candidate")
                );
                pc.addIceCandidate(candidate);
            }
        }
    }

    /**
     * Send a message through the signaling server
     *
     * @param to      id of recipient
     * @param type    type of message
     * @param payload payload of message
     * @throws JSONException
     */
    public void sendMessage(String to, String type, JSONObject payload) throws JSONException {
        JSONObject message = new JSONObject();
        message.put("to", to);
        message.put("type", type);
        message.put("payload", payload);
        mSocket.emit("message", message);
        Log.d(TAG, "WebRtcClient socket send " + type + " to " + to + " payload:" + payload);
    }

    public class MessageHandler {
        private HashMap<String, Command> commandMap;

        public MessageHandler() {
            this.commandMap = new HashMap<>();
            commandMap.put("init", new CreateOfferCommand());
            commandMap.put("offer", new CreateAnswerCommand());
            commandMap.put("answer", new SetRemoteSDPCommand());
            commandMap.put("candidate", new AddIceCandidateCommand());
        }

        public Emitter.Listener onMessage = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    JSONObject data = (JSONObject) args[0];
//                    String info = (String) args[0];
//                    JSONObject data = new JSONObject(info);
                    String from = data.optString("from");
                    String type = data.optString("type");
                    Log.d(TAG, "WebRtcClient socket received " + type + " from " + from);
                    JSONObject payload = null;
                    if (!type.equals("init")) {
                        payload = data.optJSONObject("payload");
                    }
                    // if peer is unknown, try to add him
                    if (!peers.containsKey(from)) {
                        // if MAX_PEER is reach, ignore the call
                        int endPoint = findEndPoint();
                        if (endPoint != MAX_PEER) {
                            Peer peer = addPeer(from, endPoint);
                            peer.pc.addStream(mLocalMediaStream);
                            commandMap.get(type).execute(from, payload);
                        }
                    } else {
                        Command command = commandMap.get(type);
                        if(command!=null){
                            command.execute(from, payload);
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        public Emitter.Listener onId = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                String id = (String) args[0];
                Log.i(TAG,"WebRtcClient mListener.onReady(id)");
                mListener.onReady(id);
                Log.i(TAG,"WebRtcClient  mListener.onStatusChanged(READY)");
                mListener.onStatusChanged("READY");
                Log.d(TAG, "WebRtcClient socket onId " + id);
            }
        };
    }

    public class Peer implements SdpObserver, PeerConnection.Observer {
        public PeerConnection pc;
        public String id;
        public int endPoint;

        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
            // TODO: modify sdp to use mPeerConnParams prefered codecs
            try {
                JSONObject payload = new JSONObject();
                payload.put("type", sdp.type.canonicalForm());
                payload.put("sdp", sdp.description);
                Log.d(TAG, "WebRtcClient onCreateSuccess");
                sendMessage(id, sdp.type.canonicalForm(), payload);
                pc.setLocalDescription(Peer.this, sdp);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }


        public void onIceConnectionReceivingChange(boolean var1) {

        }

        public void onIceCandidatesRemoved(IceCandidate[] var1) {

        }

        public void onAddTrack(RtpReceiver var1, MediaStream[] var2) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                removePeer(id);
                mListener.onStatusChanged("DISCONNECTED");
            }
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }

        @Override
        public void onIceCandidate(final IceCandidate candidate) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("label", candidate.sdpMLineIndex);
                payload.put("id", candidate.sdpMid);
                payload.put("candidate", candidate.sdp);
                sendMessage(id, "candidate", payload);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "WebRtcClient onAddStream " + mediaStream.label());
            // remote streams are displayed from 1 to MAX_PEER (0 is localStream)
//            mediaStream.videoTracks.get(0).addRenderer(new VideoRenderer(mRemoteRender));
//            mListener.onAddRemoteStream(mediaStream, endPoint + 1);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(TAG, "WebRtcClient onRemoveStream " + mediaStream.label());
            removePeer(id);
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        public Peer(String id, int endPoint) {
            Log.d(TAG, "WebRtcClient new Peer: " + id + " " + endPoint);
            this.pc = factory.createPeerConnection(iceServers, mPeerConnConstraints, this);
            this.id = id;
            this.endPoint = endPoint;
            pc.addStream(mLocalMediaStream); //, new MediaConstraints()
        }
    }

    private Peer addPeer(String id, int endPoint) {
        Peer peer = new Peer(id, endPoint);
        peers.put(id, peer);

        endPoints[endPoint] = true;
        return peer;
    }

    private void removePeer(String id) {
        Peer peer = peers.get(id);
        peer.pc.close();
        peers.remove(peer.id);
        endPoints[peer.endPoint] = false;
    }

    public WebRtcClient(Context context, RtcListener listener, VideoCapturer capturer, PeerConnectionClient.PeerConnectionParameters params) {
        mContext = context;
        mListener = listener;
        mPeerConnParams = params;
        videoCapturer = capturer;
        PeerConnectionFactory.initializeAndroidGlobals(mContext,
                params.videoCodecHwAcceleration);
        factory = new PeerConnectionFactory(null);
//        String host = "https://" + context.getString(R.string.host) + ":" + context.getString(R.string.port) + "/";

        String host = RtcActivity.host;
        Log.i("TAG","host :"+host);
        try {
            mSocket = IO.socket(host);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        mSocket.on("id", messageHandler.onId); //id 的监听的事情
        mSocket.on("message", messageHandler.onMessage);
        mSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() { //回调监听 连接
            @Override
            public void call(Object... args) {
                Log.d(TAG, "WebRtcClient socket state connect");
            }
        });
        mSocket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {//回调监听 关闭连接
            @Override
            public void call(Object... args) {
                Log.d(TAG, "WebRtcClient socket state disconnect");
            }
        });
        mSocket.on(Socket.EVENT_ERROR, new Emitter.Listener() {//回调监听 错误
            @Override
            public void call(Object... args) {
                Log.d(TAG, "WebRtcClient socket state error");
            }
        });
        mSocket.connect();
        Log.d(TAG, "WebRtcClient socket start connect");
        iceServers.add(new PeerConnection.IceServer("stun:23.21.150.121"));
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));

        mPeerConnConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mPeerConnConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        mPeerConnConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
    }


    /**
     * Call this method in Activity.onDestroy()
     */
    public void onDestroy() {
        for (Peer peer : peers.values()) {
            peer.pc.dispose();
        }
        if (factory != null) {
            factory.dispose();
        }
        if (mVideoSource != null) {
            mVideoSource.dispose();
        }
        if(videoCapturer!=null){
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mSocket.disconnect();
        mSocket.close();
    }

    private int findEndPoint() {
        for (int i = 0; i < MAX_PEER; i++) if (!endPoints[i]) return i;
        return MAX_PEER;
    }

    /**
     * Start the mSocket.
     * <p>
     * Set up the local stream and notify the signaling server.
     * Call this method after onCallReady.
     *
     * @param name mSocket name
     */
    public void start(String name) {
        Log.i(TAG,"WebRtcClient initScreenCapturStream()");
        initScreenCapturStream();//开启视频流
        //开启socket-io 通道
        /*
        try {
            JSONObject message = new JSONObject();
            message.put("name", name);
            mSocket.emit("readyToStream", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        */
    }
    public void socketstart(String name){
        try {
            JSONObject message = new JSONObject();
            message.put("name", name);
            mSocket.emit("readyToStream", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void initScreenCapturStream() {
        Log.i(TAG,"startCapture()");
        Log.i(TAG,"mPeerConnParams.videoWidth"+mPeerConnParams.videoWidth);
        Log.i(TAG,"mPeerConnParams.videoHeight"+mPeerConnParams.videoHeight);
        Log.i(TAG,"mPeerConnParams.videoFps"+mPeerConnParams.videoFps);
        long width =  ((long)mPeerConnParams.videoWidth)<<40|((long)mPeerConnParams.contentWidth)<<20|mPeerConnParams.bufferWidth;
        long height = ((long)mPeerConnParams.videoHeight)<<40|((long)mPeerConnParams.contentHeight)<<20|mPeerConnParams.bufferHeight;
        mLocalMediaStream = factory.createLocalMediaStream("ARDAMS");
        MediaConstraints videoConstraints = new MediaConstraints();
        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxHeight", Integer.toString(mPeerConnParams.videoHeight)));
        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxWidth", Integer.toString(mPeerConnParams.videoWidth)));
        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxFrameRate", Integer.toString(mPeerConnParams.videoFps)));
//        VideoCapturer capturer = createScreenCapturer();
        mVideoSource = factory.createVideoSource(videoCapturer);
        videoCapturer.startCapture(width, height, mPeerConnParams.videoFps);
        VideoTrack localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, mVideoSource);
        localVideoTrack.setEnabled(true);
        mLocalMediaStream.addTrack(factory.createVideoTrack("ARDAMSv0", mVideoSource));
//        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        MediaConstraints audioConstraints = new MediaConstraints();
        //回声消除
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation2", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googDAEchoCancellation", "true"));
//自动增益
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl2", "true"));
//噪音处理
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"));
//高音过滤
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAudioMirroring", "false"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));

        AudioSource audioSource = factory.createAudioSource(audioConstraints);
        AudioTrack audioTrack  = factory.createAudioTrack("ARDAMSa0", audioSource);
        mLocalMediaStream.addTrack(audioTrack);
//        mLocalMediaStream.videoTracks.get(0).addRenderer(new VideoRenderer(mLocalRender));
//        mListener.onLocalStream(mLocalMediaStream);
        mListener.onStatusChanged("STREAMING");
    }


}

package DANAPI;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import CSMAPI.CSMAPI;
import CSMAPI.CSMAPI.CSMError;

public class DAN implements DANAPI {
    // ************************************************ //
    // * Constants or Semi-constants (Seldom changed) * //
    // ************************************************ //
    private final String VERSION = "20160514";
    private String log_tag = "DAN";
    private final String dan_log_tag = "DAN";
    private final String DEFAULT_EC_HOST = "http://openmtc.darkgerm.com:9999";
    private final int EC_BROADCAST_PORT = 17000;
    private final Long HEART_BEAT_DEAD_MILLISECOND = 3000l;
    private long request_interval = 150;
    

    // ******************* //
    // * Private classes * //
    // ******************* //
    
    /*
     * SearchECThread searches EC in the same LAN by receiving UDP broadcast packets
     * 
     * SearchECThread.kill() stops the thread
     */
    private class SearchECThread extends Thread {
        private DatagramSocket socket;

        public void kill () {
            logging("SearchECThread.kill()");
            socket.close();
            try {
                this.join();
            } catch (InterruptedException e) {
                logging("SearchECThread.kill(): InterruptedException");
            }
            logging("SearchECThread.kill(): killed");
        }

        public void run () {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", EC_BROADCAST_PORT));
                byte[] lmessage = new byte[20];
                DatagramPacket packet = new DatagramPacket(lmessage, lmessage.length);
                while (true) {
                    socket.receive(packet);
                    String input_data = new String( lmessage, 0, packet.getLength() );
                    if (input_data.equals("easyconnect")) {
                        // It's easyconnect packet
                        InetAddress ec_raw_addr = packet.getAddress();
                        String ec_endpoint = "http://"+ ec_raw_addr.getHostAddress() +":9999";
                        synchronized (detected_ec_heartbeat) {
                            if (!detected_ec_heartbeat.containsKey(ec_endpoint)) {
                                logging("FOUND_NEW_EC: %s", ec_endpoint);
                                broadcast_event(Event.FOUND_NEW_EC, ec_endpoint);
                            }
                            detected_ec_heartbeat.put(ec_endpoint, System.currentTimeMillis());
                        }
                    }
                }
            } catch (IOException e) {
                logging("SearchECThread: IOException");
            }
        }
    }
    
    /*
     * SessionThread handles the session status between DAN and EasyConnect.
     *      ``session_status`` records the status,
     *          and this value SHOULD NOT be true after disconnect() and before connect().
     * 
     * SessionThread.connect(String) registers to the given EasyConnect host
     *      This method retries 3 times, between each retry it sleeps 2000 milliseconds
     *      This method is non-blocking, because it notifies odf_receivers in ``event_odf_receivers``.
     * 
     * SessionThread.disconnect() deregisters from previous connected EasyConnect host
     *      This method retries 3 times, between each retry it sleeps 2000 milliseconds
     *      This method is blocking, because it doesn't generate events. Re-register also needs it to be blocking.
     * 
     * SessionThread.kill() stops the thread
     */
    private class SessionThread extends Thread {
        private final int RETRY_COUNT = 3;
        private final int RETRY_INTERVAL = 2000;
        private final int REGISTER = 0;
        private final int DEREGISTER = 1;
        private boolean session_status;
        
        private class SessionCommand {
            public int action;
            public String ec_endpoint;
            public SessionCommand (int action, String ec_endpoint) {
                this.action = action;
                this.ec_endpoint = ec_endpoint;
            }
        }

        private final LinkedBlockingQueue<SessionCommand> command_channel =
                new LinkedBlockingQueue<SessionCommand>();
        
        public void register (String ec_endpoint) {
            logging("SessionThread.connect(%s)", ec_endpoint);
            SessionCommand sc = new SessionCommand(REGISTER, ec_endpoint);
            try {
                command_channel.add(sc);
            } catch (IllegalStateException e) {
                logging("SessionThread.connect(): IllegalStateException, command channel is full");
            }
        }
        
        public void deregister () {
            logging("SessionThread.disconnect()");
            SessionCommand sc = new SessionCommand(DEREGISTER, "");
            try {
                command_channel.add(sc);
                synchronized (sc) {
                    sc.wait();
                }
            } catch (IllegalStateException e) {
                logging("SessionThread.connect(): IllegalStateException, command channel is full");
            } catch (InterruptedException e) {
                logging("SessionThread.disconnect(): InterruptedException, response_channel is interrupted");
            }
        }
        
        @Override
        public void run () {
            try {
                while (true) {
                    SessionCommand sc = command_channel.take();
                    switch (sc.action) {
                    case REGISTER:
                        logging("SessionThread.run(): REGISTER: %s", sc.ec_endpoint);
                        if (session_status && !CSMAPI.ENDPOINT.equals(sc.ec_endpoint)) {
                            logging("SessionThread.run(): REGISTER: Already registered to another EC");
                        } else {
                            CSMAPI.ENDPOINT = sc.ec_endpoint;
                            for (int i = 0; i < RETRY_COUNT; i++) {
                                try {
                                    session_status = CSMAPI.register(d_id, profile);
                                    logging("SessionThread.run(): REGISTER: %s: %b", CSMAPI.ENDPOINT, session_status);
                                    if (session_status) {
                                        break;
                                    }
                                } catch (CSMError e) {
                                    logging("SessionThread.run(): REGISTER: CSMError");
                                }
                                logging("SessionThread.run(): REGISTER: Wait %d milliseconds before retry", RETRY_INTERVAL);
                                Thread.sleep(RETRY_INTERVAL);
                            }
                            
                            if (session_status) {
                                broadcast_event(Event.REGISTER_SUCCEED, CSMAPI.ENDPOINT);
                            } else {
                                logging("SessionThread.run(): REGISTER: Give up");
                                broadcast_event(Event.REGISTER_FAILED, CSMAPI.ENDPOINT);
                            }
                        }
                        break;
                        
                    case DEREGISTER:
                        logging("SessionThread.run(): DEREGISTER: %s", CSMAPI.ENDPOINT);
                        if (!session_status) {
                            logging("SessionThread.run(): DEREGISTER: Not registered to any EC, abort");
                        } else {
                            boolean deregister_success = false;
                            for (int i = 0; i < RETRY_COUNT; i++) {
                                try {
                                    deregister_success = CSMAPI.deregister(d_id);
                                    logging("SessionThread.run(): DEREGISTER: %s: %b", CSMAPI.ENDPOINT, deregister_success);
                                    if (deregister_success) {
                                        break;
                                    }
                                } catch (CSMError e) {
                                    logging("SessionThread.run(): DEREGISTER: CSMError");
                                }
                                logging("SessionThread.run(): DEREGISTER: Wait %d milliseconds before retry", RETRY_INTERVAL);
                                Thread.sleep(RETRY_INTERVAL);
                            }

                            if (deregister_success) {
                                broadcast_event(Event.DEREGISTER_SUCCEED, CSMAPI.ENDPOINT);
                            } else {
                                logging("SessionThread.run(): DEREGISTER: Give up");
                                broadcast_event(Event.DEREGISTER_FAILED, CSMAPI.ENDPOINT);
                            }
                            // No matter what result is,
                            //  set session_status to false because I've already retry <RETRY_COUNT> times
                            session_status = false;
                        }
                        synchronized (sc) {
                            sc.notify();
                        }
                        break;
                        
                    default:
                        break;
                    }
                }
            } catch (InterruptedException e) {
                logging("SessionThread.run(): InterruptedException");
            }
        }
        
        public void kill () {
            logging("SessionThread.kill()");
            if (status()) {
                deregister();
            }
            
            this.interrupt();
            try {
                this.join();
            } catch (InterruptedException e) {
                logging("SessionThread.kill(): InterruptedException");
            }
            logging("SessionThread.kill(): killed");
        }
        
        public boolean status () {
            return session_status;
        }
    }

    private class UpStreamThread extends Thread {
        String feature;
        final LinkedBlockingQueue<JSONArray> queue = new LinkedBlockingQueue<JSONArray>();
        AbstractReducer reducer;

        public UpStreamThread (String feature) {
            this.feature = feature;
            this.reducer = AbstractReducer.LAST;
        }

        public void enqueue (JSONArray data, AbstractReducer reducer) {
            enqueue(data);
            this.reducer = reducer;
        }

        public void enqueue (JSONArray data) {
            try {
                queue.put(data);
            } catch (InterruptedException e) {
                logging("UpStreamThread(%s).enqueue(): InterruptedException", feature);
            }
        }

        public void kill () {
            this.interrupt();
        }

        public void run () {
            logging("UpStreamThread(%s) starts", feature);
            try {
                while (!isInterrupted()) {
                    Thread.sleep(request_interval);

                    JSONArray data = queue.take();
                    int count = queue.size();
                    for (int i = 1; i <= count; i++) {
                        JSONArray tmp = queue.take();
                        data = reducer.reduce(data, tmp, i, count);
                    }
                    
                    if (session_status()) {
                        logging("UpStreamThread(%s).run(): push %s", feature, data.toString());
                        try {
                            CSMAPI.push(d_id, feature, data);
                            broadcast_event(Event.PUSH_SUCCEED, feature);
                        } catch (CSMError e) {
                            logging("UpStreamThread(%s).run(): CSMError", feature);
                            broadcast_event(Event.PUSH_FAILED, feature);
                        }
                    } else {
                        logging("UpStreamThread(%s).run(): skip. (ec_status == false)", feature);
                    }
                }
            } catch (InterruptedException e) {
                logging("UpStreamThread(%s).run(): InterruptedException", feature);
            }
            logging("UpStreamThread(%s) ends", feature);
        }
    }

    private class DownStreamThread extends Thread {
        String feature;
        AbstractODFReceiver odf_receiver;
        String timestamp;

        public DownStreamThread (String feature, AbstractODFReceiver callback) {
            this.feature = feature;
            this.odf_receiver = callback;
            this.timestamp = "";
        }
        
        public boolean has_odf_receiver (AbstractODFReceiver odf_receiver) {
            return this.odf_receiver.equals(odf_receiver);
        }

        private void deliver_data (JSONArray dataset) throws JSONException {
            logging("DownStreamThread(%s).deliver_data(): %s", feature, dataset.toString());
            if (dataset.length() == 0) {
                logging("DownStreamThread(%s).deliver_data(): No any data", feature);
                return;
            }
            
            String new_timestamp = dataset.getJSONArray(0).getString(0);
            JSONArray new_data = dataset.getJSONArray(0).getJSONArray(1);
            if (new_timestamp.equals(timestamp)) {
                logging("DownStreamThread(%s).deliver_data(): No new data", feature);
                return;
            }
            
            timestamp = new_timestamp;
            odf_receiver.receive(feature, new ODFObject(new_timestamp, new_data));
        }

        public void kill () {
            this.interrupt();
        }

        public void run () {
            logging("DownStreamThread(%s) starts", feature);
            try {
                while (!isInterrupted()) {
                    try{
                        Thread.sleep(request_interval);
                        if (session_status()) {
                            logging("DownStreamThread(%s).run(): pull", feature);
                            deliver_data(CSMAPI.pull(d_id, feature));
                        } else {
                            logging("DownStreamThread(%s).run(): skip. (ec_status == false)", feature);
                        }
                    } catch (JSONException e) {
                        logging("DownStreamThread(%s).run(): JSONException", feature);
                    } catch (CSMError e) {
                        logging("DownStreamThread(%s).run(): CSMError", feature);
                        broadcast_event(Event.PULL_FAILED, feature);
                    }
                }
            } catch (InterruptedException e) {
                logging("DownStreamThread(%s).run(): InterruptedException", feature);
            }
            logging("DownStreamThread(%s) ends", feature);
        }
    }
    
    
    // ********************** //
    // * Private Containers * //
    // ********************** //
    final Set<AbstractODFReceiver> event_odf_receivers = Collections.synchronizedSet(new HashSet<AbstractODFReceiver>());
    final ConcurrentHashMap<String, UpStreamThread> upstream_thread_pool = new ConcurrentHashMap<String, UpStreamThread>();
    final ConcurrentHashMap<String, DownStreamThread> downstream_thread_pool = new ConcurrentHashMap<String, DownStreamThread>();
    final SearchECThread search_ec_thread = new SearchECThread();
    final SessionThread session_thread = new SessionThread();
    // LinkedHashMap is ordered-map
    final Map<String, Long> detected_ec_heartbeat = Collections.synchronizedMap(new LinkedHashMap<String, Long>());
    String d_id;
    JSONObject profile;
    

    // ************** //
    // * Public API * //
    // ************** //
    
    public String version () {
        return VERSION;
    }
    
    public void set_log_tag (String log_tag) {
        this.log_tag = log_tag;
        CSMAPI.set_log_tag(log_tag);
    }
    
    public void init (AbstractODFReceiver init_odf_receiver) {
        logging("init()");
        search_ec_thread.start();
        session_thread.start();
        
        CSMAPI.ENDPOINT = DEFAULT_EC_HOST;
        set_request_interval(150);

        synchronized (event_odf_receivers) {
            event_odf_receivers.clear();
            event_odf_receivers.add(init_odf_receiver);
        }
        upstream_thread_pool.clear();
        downstream_thread_pool.clear();
        synchronized (detected_ec_heartbeat) {
            detected_ec_heartbeat.clear();
        }
        logging("init(): finished");
    }

    public String get_clean_mac_addr (String mac_addr) {
        return mac_addr.replace(":", "");
    }

    public String get_d_id (String mac_addr) {
        return get_clean_mac_addr(mac_addr);
    }

    public String get_d_name () {
        try {
            if (profile == null) {
                return "Error";
            }
            return profile.getString("d_name");
        } catch (JSONException e) {
            logging("get_d_name(): JSONException");
        }
        return "Error";
    }

    public void register (String d_id, JSONObject profile) {
        register(CSMAPI.ENDPOINT, d_id, profile);
    }
    
    public void register (String ec_endpoint, String d_id, JSONObject profile) {
        this.d_id = d_id;
        this.profile = profile;
        if (!this.profile.has("is_sim")) {
            try {
                this.profile.put("is_sim", false);
            } catch (JSONException e) {
                logging("register(): JSONException");
            }
        }

        session_thread.register(ec_endpoint);
    }
    
    public void reregister (String ec_endpoint) {
        logging("reregister(%s)", ec_endpoint);
        session_thread.deregister();
        session_thread.register(ec_endpoint);
    }

    public void push (String feature, double[] data) {
        push(feature, data, AbstractReducer.LAST);
    }

    public void push (String feature, double[] data, AbstractReducer reducer) {
        JSONArray tmp = new JSONArray();
        for (int i = 0; i < data.length; i++) {
            tmp.put(data[i]);
        }
        push(feature, tmp, reducer);
    }

    public void push (String feature, float[] data) {
        push(feature, data, AbstractReducer.LAST);
    }

    public void push (String feature, float[] data, AbstractReducer reducer) {
        JSONArray tmp = new JSONArray();
        for (int i = 0; i < data.length; i++) {
            tmp.put(data[i]);
        }
        push(feature, tmp, reducer);
    }

    public void push (String feature, int[] data) {
        push(feature, data, AbstractReducer.LAST);
    }
    
    public void push (String feature, int[] data, AbstractReducer reducer) {
        JSONArray tmp = new JSONArray();
        for (int i = 0; i < data.length; i++) {
            tmp.put(data[i]);
        }
        push(feature, tmp, reducer);
    }
    
    public void push (String feature, JSONArray data) {
        push(feature, data, AbstractReducer.LAST);
    }
    
    public void push (String feature, JSONArray data, AbstractReducer reducer) {
        if (!device_feature_exists(feature)) {
            logging("push(%s): feature not exists", feature);
            return;
        }
        if (!upstream_thread_pool.containsKey(feature)) {
            UpStreamThread ust = new UpStreamThread(feature);
            upstream_thread_pool.put(feature, ust);
            ust.start();
        }
        UpStreamThread ust = upstream_thread_pool.get(feature);
        ust.enqueue(data, reducer);
    }
    
    public void subscribe (String[] odf_list, AbstractODFReceiver odf_receiver) {
        for (String odf: odf_list) {
            subscribe(odf, odf_receiver);
        }
    }

    public void subscribe (String odf_list, AbstractODFReceiver odf_receiver) {
        if (odf_list.equals(CONTROL_CHANNEL)) {
            synchronized (event_odf_receivers) {
                event_odf_receivers.add(odf_receiver);
            }
        } else {
            if (!device_feature_exists(odf_list)) {
                logging("subscribe(%s): feature not exists", odf_list);
                return;
            }
            if (!downstream_thread_pool.containsKey(odf_list)) {
                DownStreamThread dst = new DownStreamThread(odf_list, odf_receiver);
                downstream_thread_pool.put(odf_list, dst);
                dst.start();
            }
        }
    }

    public void unsubscribe (String feature) {
        if (feature.equals(CONTROL_CHANNEL)) {
            synchronized (event_odf_receivers) {
                event_odf_receivers.clear();
            }
        } else {
            DownStreamThread down_stream_thread = downstream_thread_pool.get(feature);
            if (down_stream_thread == null) {
                return;
            }
            down_stream_thread.kill();
            try {
                down_stream_thread.join();
            } catch (InterruptedException e) {
                logging("unsubscribe(): DownStreamThread: InterruptedException");
            }
            downstream_thread_pool.remove(feature);
        }
    }

    public void unsubscribe (AbstractODFReceiver odf_receiver) {
        synchronized (event_odf_receivers) {
            event_odf_receivers.remove(odf_receiver);
        }

        for (Map.Entry<String, DownStreamThread> p: downstream_thread_pool.entrySet()) {
            String feature = p.getKey();
            DownStreamThread down_stream_thread = p.getValue();
            if (down_stream_thread.has_odf_receiver(odf_receiver)) {
                down_stream_thread.kill();
                try {
                    down_stream_thread.join();
                } catch (InterruptedException e) {
                    logging("unsubscribe(): DownStreamThread: InterruptedException");
                }
                downstream_thread_pool.remove(feature);
                break;
            }
        }
    }

    public void deregister () {
        session_thread.deregister();
    }
    
    public void shutdown () {
        logging("shutdown()");
        
        for (Map.Entry<String, UpStreamThread> p: upstream_thread_pool.entrySet()) {
            UpStreamThread t = p.getValue();
            t.kill();
            try {
                t.join();
            } catch (InterruptedException e) {
                logging("shutdown(): UpStreamThread: InterruptedException");
            }
        }
        upstream_thread_pool.clear();

        for (Map.Entry<String, DownStreamThread> p: downstream_thread_pool.entrySet()) {
            DownStreamThread t = p.getValue();
            t.kill();
            try {
                t.join();
            } catch (InterruptedException e) {
                logging("shutdown(): DownStreamThread: InterruptedException");
            }
        }
        downstream_thread_pool.clear();
        
        search_ec_thread.kill();
        session_thread.kill();

        logging("shutdown(): finished");
    }
    
    public String[] available_ec () {
        ArrayList<String> t = new ArrayList<String>();
        t.add(DEFAULT_EC_HOST);
        synchronized (detected_ec_heartbeat) {
            for (Map.Entry<String, Long> p: detected_ec_heartbeat.entrySet()) {
                if (System.currentTimeMillis() - p.getValue() < HEART_BEAT_DEAD_MILLISECOND) {
                    t.add(p.getKey());
                } else {
                    detected_ec_heartbeat.remove(p);
                }
            }
        }
        return t.toArray(new String[]{});
    }
    
    public String ec_endpoint () {
        return CSMAPI.ENDPOINT;
    }
    
    public boolean session_status () {
        return session_thread.status();
    }

    public long get_request_interval () {
        return request_interval;
    }

    public void set_request_interval (long request_interval) {
        if (request_interval > 0) {
            logging("set_request_interval(%d)", request_interval);
            this.request_interval = request_interval;
        }
    }


    // ***************************** //
    // * Internal Helper Functions * //
    // ***************************** //
    private boolean device_feature_exists (String feature) {
        JSONArray df_list = profile.getJSONArray("df_list");
        for (int i = 0; i < df_list.length(); i++) {
            if (df_list.getString(i).equals(feature)) {
                return true;
            }
        }
        return false;
    }
    
    public void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    private void logging (String message) {
        System.out.printf("[%s][%s] %s%n", log_tag, dan_log_tag, message);
    }

    private void broadcast_event (Event event, String message) {
        logging("broadcast_event()");
        synchronized (event_odf_receivers) {
            for (AbstractODFReceiver handler: event_odf_receivers) {
                handler.receive(CONTROL_CHANNEL, new ODFObject(event, message));
            }
        }
    }
}


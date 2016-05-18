package DANapi;

import org.json.JSONArray;
import org.json.JSONObject;

public interface DANapi {
    public final String CONTROL_CHANNEL = "Control_channel";
    
    static public enum Event {
        INITIALIZATION_FAILED,
        INITIALIZED,
        SEARCHING_STARTED,
        NEW_EC_DISCOVERED,
        SEARCHING_STOPPED,
        REGISTER_FAILED,
        REGISTER_SUCCEED,
        PUSH_FAILED,
        PUSH_SUCCEED,
        PULL_FAILED,
        DEREGISTER_FAILED,
        DEREGISTER_SUCCEED,
    };
    
    static public class ODFObject {
        // data part
        public String timestamp;
        public JSONArray data;
        
        // event part
        public Event event;
        public String message;

        public ODFObject (Event event, String message) {
            this.timestamp = null;
            this.data = null;
            this.event = event;
            this.message = message;
        }

        public ODFObject (String timestamp, JSONArray data) {
            this.timestamp = timestamp;
            this.data = data;
            this.event = null;
            this.message = null;
        }
    }

    interface ODFHandler {
        void receive (String odf, ODFObject odf_object);
    }
    
    interface Reducer {
        JSONArray reduce (JSONArray a, JSONArray b, int b_index, int last_index);
        static final Reducer LAST = new Reducer () {
            @Override
            public JSONArray reduce(JSONArray a, JSONArray b, int b_index, int last_index) {
                return b;
            }
        };
    }
    
    String version ();
    void set_log_tag (String log_tag);
    void init (ODFHandler init_odf_handler);
    String get_clean_mac_addr (String mac_addr);
    String get_d_id (String mac_addr);
    String get_d_name ();
    void register (String d_id, JSONObject profile);
    void register (String ec_endpoint, String d_id, JSONObject profile);
    void reregister (String ec_endpoint);
    void push (String idf, double[] data);
    void push (String idf, double[] data, Reducer reducer);
    void push (String idf, float[] data);
    void push (String idf, float[] data, Reducer reducer);
    void push (String idf, int[] data);
    void push (String idf, int[] data, Reducer reducer);
    void push (String idf, JSONArray data);
    void push (String idf, JSONArray data, Reducer reducer);
    void subscribe (String odf_list[], ODFHandler odf_handler);
    void subscribe (String odf, ODFHandler odf_handler);
    void unsubscribe (ODFHandler odf_handler);
    void deregister ();
    void shutdown ();
    String[] available_ec ();
    String ec_endpoint ();
    boolean session_status ();
    long get_request_interval ();
    void set_request_interval (long request_interval);
}
package DANAPI;

import org.json.JSONArray;
import org.json.JSONObject;

public interface DANAPI {
    public final String CONTROL_CHANNEL = "Control_channel";
    
    static public enum Event {
        FOUND_NEW_EC,
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

    static public abstract class AbstractODFReceiver {
        abstract public void receive (String odf, ODFObject odf_object);
    }
    
    static public abstract class AbstractReducer {
        abstract public JSONArray reduce (JSONArray a, JSONArray b, int b_index, int last_index);
        static public final AbstractReducer LAST = new AbstractReducer () {
            @Override
            public JSONArray reduce(JSONArray a, JSONArray b, int b_index, int last_index) {
                return b;
            }
        };
    }
    
    String version ();
    void set_log_tag (String log_tag);
    void init (AbstractODFReceiver init_odf_receiver);
    String get_clean_mac_addr (String mac_addr);
    String get_d_id (String mac_addr);
    String get_d_name ();
    void register (String d_id, JSONObject profile);
    void register (String ec_endpoint, String d_id, JSONObject profile);
    void reregister (String ec_endpoint);
    void push (String feature, double[] data);
    void push (String feature, double[] data, AbstractReducer reducer);
    void push (String feature, float[] data);
    void push (String feature, float[] data, AbstractReducer reducer);
    void push (String feature, int[] data);
    void push (String feature, int[] data, AbstractReducer reducer);
    void push (String feature, JSONArray data);
    void push (String feature, JSONArray data, AbstractReducer reducer);
    void subscribe (String odf_list[], AbstractODFReceiver odf_receiver);
    void subscribe (String odf_list, AbstractODFReceiver odf_receiver);
    void unsubscribe (String feature);
    void unsubscribe (AbstractODFReceiver odf_receiver);
    void deregister ();
    void shutdown ();
    String[] available_ec ();
    String ec_endpoint ();
    boolean session_status ();
    long get_request_interval ();
    void set_request_interval (long request_interval);
}
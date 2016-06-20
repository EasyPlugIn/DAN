package DAN;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import CSMapi.CSMapi;
import CSMapi.CSMapi.CSMError;

public class DAN extends Thread {
    interface DAN2DAI {
        void pull(String odf_name, JSONArray data);
    }
    
    final int IOTTALK_BROADCAST_PORT = 17000;
    final int RETRY_COUNT = 3;
    final int RETRY_INTERVAL = 2000;
    public final String log_tag = "DAN";
    DAN2DAI dai_2_dai_ref;
    String d_id;
    boolean registered;
    String[] df_list;
    boolean[] df_selected;
    boolean[] df_is_odf;
    String[] df_timestamp;
    
    public boolean init(String endpoint, String d_id, JSONObject profile, DAN2DAI dai_2_dai_ref) {
        logging("init()");
        this.d_id = d_id;
        this.dai_2_dai_ref = dai_2_dai_ref;
        if (!registered) {
            if (endpoint == null) {
                CSMapi.ENDPOINT = search();
            } else {
                CSMapi.ENDPOINT = endpoint;
            }
        }
        
        JSONArray json_df_list = profile.getJSONArray("df_list");
        df_list = new String[json_df_list.length() + 1];
        df_selected = new boolean[df_list.length];
        df_is_odf = new boolean[df_list.length];
        df_timestamp = new String[df_list.length];
        
        df_list[0] = "__Ctl_O__";
        df_selected[0] = true;
        df_is_odf[0] = true;
        df_timestamp[0] = "";
        
        for (int i = 1; i < df_list.length; i++) {
            df_list[i] = json_df_list.getString(i - 1);
            df_selected[i] = false;
            df_is_odf[i] = true;
            df_timestamp[i] = "";
        }
        
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                if (CSMapi.register(d_id, profile)) {
                    logging("init(): Register succeed: %s", CSMapi.ENDPOINT);
                    if (!registered) {
                        registered = true;
                        this.start();
                    }
                    return true;
                }
            } catch (CSMError e) {
                logging("init(): REGISTER: CSMError");
            }
            logging("init(): Register failed, wait %d milliseconds before retry", RETRY_INTERVAL);
            try {
                Thread.sleep(RETRY_INTERVAL);
            } catch (InterruptedException e) {
                logging("init(): InterruptedException");
            }
        }
        return false;
    }
    
    public boolean push(String idf_name, JSONArray data) {
        logging("push(%s)", idf_name);
        try {
            if (idf_name.equals("Control")) {
                idf_name = "__Ctl_I__";
            }
            for (int i = 0; i < df_list.length; i++) {
                if (idf_name.equals(df_list[i])) {
                    df_is_odf[i] = false;
                }
            }
            return CSMapi.push(d_id, idf_name, data);
        } catch (CSMError e) {
            logging("push(): CSMError: %s", e.getMessage());
            return false;
        }
    }
    
    public boolean deregister() {
        logging("deregister()");
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                if (CSMapi.deregister(d_id)) {
                    logging("deregister(): Deregister succeed: %s", CSMapi.ENDPOINT);
                    registered = false;
                    this.interrupt();
                    return true;
                }
            } catch (CSMError e) {
                logging("deregister(): DEREGISTER: CSMError");
            }
            logging("deregister(): Deregister failed, wait %d milliseconds before retry", RETRY_INTERVAL);
            try {
                Thread.sleep(RETRY_INTERVAL);
            } catch (InterruptedException e) {
                logging("deregister(): InterruptedException");
            }
        }
        return false;
    }
    
    public void run () {
        logging("Polling: starts");
        while (!isInterrupted()) {
            try{
                if (registered) {
                    for (int i = 0; i < df_list.length; i++) {
                        if (isInterrupted()) {
                            break;
                        }
                        if (!df_is_odf[i] || !df_selected[i]) {
                            continue;
                        }
                        JSONArray dataset = CSMapi.pull(d_id, df_list[i]);
                        if (dataset.length() == 0) {
                            continue;
                        }
                        String timestamp = dataset.getJSONArray(0).getString(0);
                        if (timestamp.equals(df_timestamp[i])) {
                            continue;
                        }
                        df_timestamp[i] = timestamp;
                        JSONArray data = dataset.getJSONArray(0).getJSONArray(1);
                        dai_2_dai_ref.pull(df_list[i], data);
                    }
                } else {
                    logging("Polling: not registered, skip polling");
                }
            } catch (JSONException e) {
                logging("Polling: JSONException");
            } catch (CSMError e) {
                logging("Polling: CSMError");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                logging("Polling: InterruptedException");
            }
        }
        logging("Polling: stops");
    }

    // ***************************** //
    // * Internal Helper Functions * //
    // ***************************** //
    
    String search () {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", IOTTALK_BROADCAST_PORT));
            byte[] lmessage = new byte[20];
            DatagramPacket packet = new DatagramPacket(lmessage, lmessage.length);
            while (true) {
                socket.receive(packet);
                String broadcast_message = new String(lmessage, 0, packet.getLength());
                if (broadcast_message.equals("easyconnect")) {
                    InetAddress ec_addr = packet.getAddress();
                    socket.close();
                    return "http://"+ ec_addr.getHostAddress() +":9999";
                }
            }
        } catch (IOException e) {
            logging("init(): IOException");
            return null;
        }
    }
    
    void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    void logging (String message) {
        System.out.printf("[%s][DAN] %s%n", log_tag, message);
    }
}
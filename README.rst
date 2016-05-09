===
DAN
===
EasyConnect Device Application to Network 實作，使用 `CSMAPI <https://github.com/EasyPlugIn/CSMAPI>`_ 。


..  contents::


觀念
-----
* ``DAN`` 使用事件驅動的設計方式，API 不一定有回傳值，而是透過 ``Subscriber`` 回傳結果
* ``DAN.push()`` 並不會立刻送出資料，而會把資料放入內部的 Queue，每 150 ms 向 EasyConnect CSM 送出

  - 若資料產生的速度很快（例如 Accelerometer），中間的資料預設會全部被 ``DAN`` 丟棄，這個行為可以用 ``Reducer`` 改變
  - 送出資料的週期（150 ms）可以透過 ``DAN.set_request_interval()`` 設定，即時生效，不需重啟 ``DAN``

* ``DAN.subscribe()`` 用來取得 DAN 產生的事件或是 ODF data，對於每個 Device Feature 只需呼叫一次，DAN 會在內部開啟專門的 ``Thread`` 週期性的取得資料，並在有新資料時主動發出通知

  - ``DAN.subscribe()`` 會比對 timestamp，只會在有新資料時發出通知
  - DAN 產生的事件被視為一個特殊的 ODF，稱為 ``"Control_channel"``

* ``DAN`` 內部會開啟許多 ``Thread`` ，請務必在程式結束前呼叫 ``DAN.shutdown()`` 以將所有 ``Thread`` 關閉
* ``DAN.register()`` 、 ``DAN.deregister()`` 以及 ``DAN.reregister()`` 會把動作放入 Queue 中依次執行

  - 動作之間無法互相中斷，在每個動作完成後，下一個動作才會被執行


API
----

``Event``
```````````
從 DAN 產生的事件。

+--------------------+-------------------+
| Event              | Meanings          |
+====================+===================+
| FOUND_NEW_EC       | 找到一個 EC       |
+--------------------+-------------------+
| REGISTER_FAILED    | 註冊失敗 [1]_     |
+--------------------+-------------------+
| REGISTER_SUCCEED   | 註冊成功          |
+--------------------+-------------------+
| PUSH_FAILED        | 送出資料失敗 [2]_ |
+--------------------+-------------------+
| PUSH_SUCCEED       | 送出資料成功      |
+--------------------+-------------------+
| PULL_FAILED        | 取得資料失敗 [2]_ |
+--------------------+-------------------+
| DEREGISTER_FAILED  | 解除註冊失敗 [1]_ |
+--------------------+-------------------+
| DEREGISTER_SUCCEED | 解除註冊成功      |
+--------------------+-------------------+

..  [1] ``REGISTER_FAILED`` 可能由許多原因導致，例如：該 EC 不存在、該 Device Model 不存在於該 EC 等等
..  [2] ``PUSH_FAILED`` 可能由許多原因導致，例如：該 Device Feature 不存在於該 EC 等等


``ODFObject``
```````````````
包含 ODF 資料或是 DAN 產生的事件

* ODF 資料

  - ``timestamp``: ODF 資料的 timestamp
  - ``data``: ODF 資料

* DAN event:

  - ``event``: DAN 所產生的事件
  - ``message``: 事件相關訊息

    +--------------------+----------------------+
    | Event              | Message              |
    +====================+======================+
    | FOUND_NEW_EC       | 搜尋到的 Endpoint    |
    +--------------------+----------------------+
    | REGISTER_FAILED    | EC 的 Endpoint       |
    +--------------------+----------------------+
    | REGISTER_SUCCEED   | EC 的 Endpoint       |
    +--------------------+----------------------+
    | PUSH_FAILED        | Push 的 Feature Name |
    +--------------------+----------------------+
    | PUSH_SUCCEED       | Push 的 Feature Name |
    +--------------------+----------------------+
    | PULL_FAILED        | Push 的 Feature Name |
    +--------------------+----------------------+
    | DEREGISTER_FAILED  | EC 的 Endpoint       |
    +--------------------+----------------------+
    | DEREGISTER_SUCCEED | EC 的 Endpoint       |
    +--------------------+----------------------+


``Subscriber``
````````````````
用來訂閱 ODF 資料

* ``public abstract void odf_handler (String feature, ODFObject odf_object);``

  - ``feature`` 為 ODF 的 Feature Name
  - ``odf_object`` 為 ODF 的資料內容


``Reducer``
`````````````
用來合併 IDF 資料

``DAN`` 每 150 ms 才會送一次資料給 EC，期間累積的所有資料會經過 ``Reducer`` 處理

* ``abstract public JSONArray reduce (JSONArray a, JSONArray b, int index, int last_index);``

  - ``a`` 為目前所累積的資料
  - ``b`` 為 Queue 中的下一筆資料
  - ``index`` 為 ``a`` 的 index 值
  - ``last_index`` 為最後一筆累積資料的 index 值

* 範例

  - 若累積了三筆資料： ``[1]`` ``[2]`` ``[4]`` ``[5]`` ，希望取平均後送往 EC ::

      public JSONArray reduce (JSONArray a, JSONArray b, int index, int last_index) {
          JSONArray ret = new JSONArray();
          try {
              if (index < last_index) {
                  ret.put(a.getDouble(0) + b.getDouble(0));
              } else {
                  ret.put((a.getDouble(0) + b.getDouble(0)) / ((double)last_index));
              }
              return ret;
          } catch (JSONException e) {
              e.printStackTrace();
          }
      }

    + ``DAN`` 會依序進行以下 Function Call

      1.  ``reduce([1], [2], 1, 3)`` -> ``[3]``
      2.  ``reduce([3], [4], 2, 3)`` -> ``[7]``
      3.  ``reduce([7], [5], 3, 3)`` -> ``[12]``
      4.  最後將 ``[12]`` 送往 EC


``static public void set_log_tag (String log_tag)``
```````````````````
設定 debug 訊息的標籤


``static public void init (Subscriber init_subscriber)``
``````````````````````````````````````````````````````````
初始化 DAN

* 參數 ``init_subscriber`` 會接收到 DAN 後續產生的所有事件，不需再另外 ``subscribe()``


``static public void register ()``
```````````````````````````````````````````````````````````````````
* ``static public void register (String d_id, JSONObject profile)``
* ``static public void register (String ec_endpoint, String d_id, JSONObject profile)``

讓 DAN 註冊至 EC

* ``ec_endpoint`` 為 EC 的 Endpoint，預設為 ``http://openmtc.darkgerm.com:9999``
* ``d_id`` 為 Device ID，建議設為 Device 的 MAC Address
* ``profile`` 為一個 ``JSONObject`` ，需有以下欄位，否則會註冊失敗

  - ``"is_sim"`` ：表明 Device 是否為模擬器，若沒有此欄位，DAN 會填入預設值 ``false``
  - ``"d_name"`` ：Device 的名稱，會顯示在 EasyConnect GUI 上
  - ``"dm_name"`` ：Device 的種類，例如 Smartphone 等等
  - ``"u_name"`` ：Device 的持有者，目前 EasyConnect 沒有帳號系統，可自行輸入
  - ``"df_list"`` ：Device Feature 列表，需為 ``JSONArray`` ，每個 Feature Name 為一個 ``String``


``static public void reregister (String ec_endpoint)``
````````````````````````````````````````````````````````
重新註冊至 ``ec_endpoint``


``static public void push ()``
````````````````````````````````
* ``static public void push (String feature, double[] data)``
* ``static public void push (String feature, double[] data, Reducer reducer)``
* ``static public void push (String feature, float[] data)``
* ``static public void push (String feature, float[] data, Reducer reducer)``
* ``static public void push (String feature, int[] data)``
* ``static public void push (String feature, int[] data, Reducer reducer)``
* ``static public void push (String feature, JSONArray data)``
* ``static public void push (String feature, JSONArray data, Reducer reducer)``

送出資料至 EC

* ``feature`` 為 Feature Name，請確保該 Feature 確實存在於該 EC
* ``data`` 為 Feature 資料，原則上為 ``JSONArray`` ，其他的形式最後都會轉為 ``JSONArray`` 才送出
* ``reducer`` 為合併資料用的物件，請參考 ``Reducer`` 的說明


``static public void subscribe (String feature, Subscriber subscriber)``
``````````````````````````````````````````````````````````````````````````
向 EC 訂閱資料

* ``feature`` 為 Feature Name，請確保該 Feature 確實存在於該 EC

  - 若 ``feature.equals("Control_channel")`` ， ``subscriber`` 會接收到 DAN 產生的事件

* ``subscriber`` 為 DAN 主動通知的接收者，請參考 ``Subscriber`` 的說明
* ``"Control_channel"`` 可以被訂閱多次
* 真實 ODF 只能被訂閱一次


``static public void unsubscribe ()``
```````````````````````````````````````
* ``static public void unsubscribe (String feature)``
* ``static public void unsubscribe (Subscriber subscriber)``

取消訂閱資料

每個 Subscriber 可以同時 ``subscribe()`` 許多 Feature，故 ``unsubscribe()`` 提供兩種形式

* 若使用 Feature Name 進行 Unsubscribe，該 Feature 的所有 Subscriber 都不會再收到新的資料
* 若使用 Subscriber 進行 Unsubscribe，該 Subscriber 不會再收到新的資料

若 Unsubscribe 的對象為真實的 ODF（非 ``"Control_channel"`` ），則對應的 Thread 會關閉


``static public void deregister ()``
``````````````````````````````````````
從目前註冊的 EC 解除註冊


``static public void shutdown ()``
````````````````````````````````````
關閉 DAN 內所有的 Thread，變為未初始化狀態


``static public long get_request_interval ()``
````````````````````````````````````````````````
取得目前每次傳送/取得資料之間的間隔


``static public void set_request_interval (long request_interval)``
`````````````````````````````````````````````````````````````````````
設定每次傳送/取得資料之間的間隔，必須為大於 ``0`` 的整數


``static public String[] available_ec ()``
````````````````````````````````````````````
取得目前可用的 EC

* EC 會定期透過 UDP Port 17000 廣播 ``"easyconnect"`` 訊息至同一個區域網路
* 若在一個區域網路內有兩台以上的 EC，DAN 會將它們的 IP Address 以及收到的時間記錄下來
* 每次 ``available_ec()`` 被呼叫時，DAN 會將超過 ``3`` 秒沒有更新的 EC 去除


``static public String ec_endpoint ()``
`````````````````````````````````````````
回傳目前連接的 EC Endpoint

此回傳值並不代表是否有成功註冊，而是下次呼叫 ``register()`` 時預設會註冊的 Endpoint


``static public boolean session_status ()``
`````````````````````````````````````````````
回傳目前的連接狀態，true 代表已成功註冊，false 代表未曾註冊或註冊失敗

此回傳值為 DAN 單方面的資訊，並不保證 EC 端的資訊相同，例如 EC 重開機以後，所有 DA 都會被視為無註冊，但 DA 端並不會立刻獲得此資訊，需在下次 push/pull 資料時才可能得知


Notice
-------
* This project depends on `org.json <http://mvnrepository.com/artifact/org.json/json>`_, which is conflicted with Android runtime library.
* The ``CSMAPI.jar`` in ``DAN.jar`` is visible in compile time, **but not visible in runtime**.

  - When exporting to ``DAN.jar`` file, remember that don't export ``libs`` folder.
  - When importing ``DAN.jar``, remember to import ``CSMAPI.jar``, or ``java.lang.NoClassDefFoundError`` caused by ``CSMAPI.CSMAPI`` class would be raised.

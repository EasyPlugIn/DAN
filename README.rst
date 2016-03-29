===
DAN
===
The EasyConnect Device Application to Network implementation, depends on CSMAPI

**Notice**: This project currently depends on `org.json <http://mvnrepository.com/artifact/org.json/json>`_, which is conflicted with Android runtime library.

When exporting to ``DAN.jar`` file, remember that don't export ``libs`` folder.

When importing ``DAN.jar``, remember to import ``CSMAPI.jar``, or ``java.lang.NoClassDefFoundError`` on ``CSMAPI.CSMAPI`` class would cause App to crash.

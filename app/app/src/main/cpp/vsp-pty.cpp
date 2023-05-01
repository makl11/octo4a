#include <cstring>
#include <jni.h>
#include <string>
#include <android/log.h>
#include <cstdio>
#include "real_pty.h"
#include <pthread.h>
#include <termios.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/types.h>
#include <cstddef>
#include <sys/wait.h>
#include <unistd.h>
#include <cstdlib>
#include <sys/stat.h>
#include <map>
#include <utility>
#include <ftw.h>
#include <cerrno>

#define BUFSIZE 512
#define SERIALPIPE_PATH "/data/data/com.octo4a/files/vsp/"
timeval SELECT_TIMEOUT = {0, 500000};

bool INITIALIZED = false;
static JavaVM *jvm = nullptr;
jclass SerialData;
jmethodID SerialData_Constructor;
jclass UsbSerialDevice;
jfieldID UsbSerialDevice_SerialIOListenerField;
jfieldID UsbSerialDevice_HexIdField;
jclass SerialIOListener;
jmethodID SerialIOListener_OnReceive;


static jint getBaudrate(speed_t baudrate) {
    switch (baudrate) {
        case B0:
            return 0;
        case B50:
            return 50;
        case B75:
            return 75;
        case B110:
            return 110;
        case B134:
            return 134;
        case B150:
            return 150;
        case B200:
            return 200;
        case B300:
            return 300;
        case B600:
            return 600;
        case B1200:
            return 1200;
        case B1800:
            return 1800;
        case B2400:
            return 2400;
        case B4800:
            return 4800;
        case B9600:
            return 9600;
        case B19200:
            return 19200;
        case B38400:
            return 38400;
        case B57600:
            return 57600;
        case B115200:
            return 115200;
        case B230400:
            return 230400;
        case B460800:
            return 460800;
        case B500000:
            return 500000;
        case B576000:
            return 576000;
        case B921600:
            return 921600;
        case B1000000:
            return 1000000;
        case B1152000:
            return 1152000;
        case B1500000:
            return 1500000;
        case B2000000:
            return 2000000;
        case B2500000:
            return 2500000;
        case B3000000:
            return 3000000;
        case B3500000:
            return 3500000;
        case B4000000:
            return 4000000;
        default:
            return 250000;
    }
}

typedef int device_id_t;

class Connection {
public:
    enum class State {
        OPEN, SHOULD_CLOSE, CLOSED,
    };
    pthread_t threadHandle{};
    jobject instance{};
    device_id_t deviceId{};
    std::string pipe_path;
    int master{};
    State state = State::OPEN;
};

device_id_t getDeviceId(JNIEnv *env, jobject instance) {
    return env->GetIntField(instance, UsbSerialDevice_HexIdField);
}

std::map<device_id_t, Connection *> cons;

void removeLeftoverSerialPipes() {
    nftw(SERIALPIPE_PATH,
         [](const char *fpath, const struct stat *sb, int typeflag, struct FTW *ftwbuf) -> int {
             int result = remove(fpath);
             if (result) perror(fpath);
             return result;
         }, 64, FTW_DEPTH | FTW_PHYS);
}

void passReceivedData(unsigned char *val, jint dataSize, speed_t baudrate, jobject instance,
                      tcflag_t cIflag, tcflag_t cOflag, tcflag_t cCflag, tcflag_t CLflag) {
    __android_log_print(ANDROID_LOG_VERBOSE, "GetEnv:", " start Callback to JNL\n");
    JNIEnv *gEnv;

    if (nullptr == jvm) {
        __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", "  No VM  \n");
        return;
    }

    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_6; // set your JNI version
    args.name = nullptr;
    args.group = nullptr;

    int getEnvStat = jvm->GetEnv(reinterpret_cast<void **>(&gEnv), JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", " not attached\n");
        if (jvm->AttachCurrentThread(&gEnv, &args) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", " Failed to attach\n");
        }
    } else if (getEnvStat == JNI_OK) {
        __android_log_print(ANDROID_LOG_VERBOSE, "GetEnv:", " JNI_OK\n");
    } else if (getEnvStat == JNI_EVERSION) {
        __android_log_print(ANDROID_LOG_ERROR, "GetEnv:", " version not supported\n");
    }

    jbyteArray serialDataArr = gEnv->NewByteArray(dataSize);
    gEnv->SetByteArrayRegion(serialDataArr, 0, dataSize, (jbyte *) val);
    jobject serialDataObj = gEnv->NewObject(SerialData, SerialData_Constructor, serialDataArr,
                                            (int) baudrate, (int) cIflag, (int) cOflag,
                                            (int) cCflag, (int) CLflag);

    jobject serialIOListener = gEnv->GetObjectField(instance,
                                                    UsbSerialDevice_SerialIOListenerField);
    gEnv->CallVoidMethod(serialIOListener, SerialIOListener_OnReceive, serialDataObj);
    gEnv->DeleteLocalRef(serialDataObj);

    if (gEnv->ExceptionCheck())
        gEnv->ExceptionDescribe();

    jvm->DetachCurrentThread();
}

static void ptyThread(const device_id_t *device_id) {
    auto con = cons[*device_id];
    char tag[32] = {};
    snprintf(tag, 32, "PtyThread[%d]", *device_id);

    int slave;
    __android_log_print(ANDROID_LOG_VERBOSE, tag, "PtyThread (%d) getting ready", *device_id);

    char name[256];
    openpty(&con->master, &slave, name, nullptr, nullptr);
    __android_log_print(ANDROID_LOG_VERBOSE, tag, "PtyThread (%d) called openpty", *device_id);

    unlink(con->pipe_path.c_str());
    symlink(name, con->pipe_path.c_str());
    __android_log_print(ANDROID_LOG_VERBOSE, tag, "SYMLINKED AT %s", con->pipe_path.c_str());

    // Prepare fds
    fd_set rfds, xfds;
    int nread, nonzero = 1;
    unsigned char buf[BUFSIZE];
    ioctl(con->master, TIOCPKT, &nonzero); // Packet mode go brr
    while (con->state == Connection::State::OPEN) {
        //        setbuf(master, NULL); // gtfu buffer

        FD_ZERO(&rfds);
        FD_SET(con->master, &rfds);
        FD_ZERO(&xfds);
        FD_SET(con->master, &xfds);

        if (!select(1 + con->master, &rfds, nullptr, &xfds, &SELECT_TIMEOUT)) continue;

        const char *r_text = (FD_ISSET(con->master, &rfds) ? "master ready for reading" : "- ");
        const char *x_text = (FD_ISSET(con->master, &xfds) ? "exception on master" : "- ");

        __android_log_print(ANDROID_LOG_VERBOSE, tag, "rfds: %s, xfds: %s\n", r_text, x_text);
        if ((nread = read(con->master, buf, BUFSIZE - 1)) < 0)
            __android_log_print(ANDROID_LOG_ERROR, tag, "read error");
        else {
            if (*buf == TIOCPKT_DATA)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "TIOCPKT_DATA\n");

            if (*buf == TIOCPKT_START)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "TIOCPKT_START\n");

            if (*buf == TIOCPKT_STOP)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "TIOCPKT_STOP\n");

            if (*buf == TIOCPKT_IOCTL)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "IOCTL\n");

            if (*buf == TIOCPKT_FLUSHREAD)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "TIOCPKT_FLUSHREAD\n");

            if (*buf == TIOCPKT_FLUSHWRITE)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "TIOCPKT_FLUSHWRITE\n");

            if (*buf == TIOCPKT_DOSTOP)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "TIOCPKT_DOSTOP\n");

            if (*buf == TIOCPKT_NOSTOP)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "TIOCPKT_NOSTOP\n");

            struct termios tio = {};
            tcgetattr(con->master, &tio);
            __android_log_print(ANDROID_LOG_VERBOSE, tag, "Baudrate: %d\n", cfgetospeed(&tio));
            if ((tio.c_cflag & CSIZE) == CS8)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "8 b i t \n");

            if ((tio.c_cflag & CSIZE) == CS7)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "7 b i t \n");

            if ((tio.c_cflag & CSIZE) == CS6)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "6 b i t \n");

            if ((tio.c_cflag & CSIZE) == CS5)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "5 b i t \n");

            if (tio.c_cflag & CSTOPB)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "2 stop bits\n");

            if (tio.c_cflag & PARENB)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "Got parity\n");

            if (tio.c_cflag & PARODD)
                __android_log_print(ANDROID_LOG_VERBOSE, tag, "Odd parity\n");

            passReceivedData(buf, nread, cfgetospeed(&tio), con->instance, tio.c_iflag, tio.c_oflag,
                             tio.c_cflag, tio.c_lflag);
        }
    }
    con->state = Connection::State::CLOSED;
}

extern "C" {
JNIEXPORT void JNICALL
Java_com_octo4a_serial_UsbSerialDevice_init(JNIEnv *env, __attribute__((unused)) jobject instance) {
    if (INITIALIZED) return;
    env->GetJavaVM(&jvm);
    SerialData = (jclass) env->NewGlobalRef(env->FindClass("com/octo4a/serial/SerialData"));
    SerialData_Constructor = env->GetMethodID(SerialData, "<init>", "([BIIIII)V");

    SerialIOListener = (jclass) env->NewGlobalRef(
            env->FindClass("com/octo4a/serial/UsbSerialIOListener"));
    SerialIOListener_OnReceive = env->GetMethodID(SerialIOListener, "onDataReceived",
                                                  "(Lcom/octo4a/serial/SerialData;)V");

    UsbSerialDevice = (jclass) env->NewGlobalRef(
            env->FindClass("com/octo4a/serial/UsbSerialDevice"));

    UsbSerialDevice_SerialIOListenerField = env->GetFieldID(UsbSerialDevice, "usbSerialIOListener",
                                                            "Lcom/octo4a/serial/UsbSerialIOListener;");

    UsbSerialDevice_HexIdField = env->GetFieldID(UsbSerialDevice, "hexId", "I");

    removeLeftoverSerialPipes();
    mkdir(SERIALPIPE_PATH, 0777);

    INITIALIZED = true;
}

JNIEXPORT void JNICALL
Java_com_octo4a_serial_UsbSerialDevice_writeData(JNIEnv *env, jobject instance, jbyteArray data) {
    auto device_id = getDeviceId(env, instance);
    auto con = cons[device_id];
    jsize numBytes = env->GetArrayLength(data);
    jbyte *lib = env->GetByteArrayElements(data, nullptr);
    write(con->master, lib, numBytes);
}

JNIEXPORT jint JNICALL
Java_com_octo4a_serial_UsbSerialDevice_getBaudrate(__attribute__((unused)) JNIEnv *env,
                                                   __attribute__((unused)) jobject obj, jint data) {
    return getBaudrate(data);
}

JNIEXPORT void JNICALL
Java_com_octo4a_serial_UsbSerialDevice_runPtyThread(JNIEnv *env, jobject instance) {
    if (!INITIALIZED) Java_com_octo4a_serial_UsbSerialDevice_init(env, instance);

    device_id_t device_id = getDeviceId(env, instance);

    if (cons.find(device_id) != cons.end()) return; // device_id is already in the map

    // Start ptyThread
    auto con = new Connection();
    con->instance = env->NewGlobalRef(instance);
    con->deviceId = device_id;
    con->pipe_path = std::string(SERIALPIPE_PATH).append(std::to_string(device_id));
    cons.emplace(device_id, con);

    pthread_create(&con->threadHandle, nullptr, (void *(*)(void *)) &ptyThread, &con->deviceId);

}

JNIEXPORT void JNICALL
Java_com_octo4a_serial_UsbSerialDevice_cancelPtyThread(JNIEnv *env, jobject instance) {
    auto device_id = getDeviceId(env, instance);

    auto device_in_map = cons.find(device_id);

    if (device_in_map == cons.end()) return; // device_id is not in the map

    auto con = device_in_map->second;

    con->state = Connection::State::SHOULD_CLOSE;

    while (con->state != Connection::State::CLOSED) {
        usleep(500); // Wait for thread to terminate
    }

    close(con->master);
    unlink(con->pipe_path.c_str());
    env->DeleteGlobalRef(con->instance);
    cons.erase(cons.find(device_id));
    delete con;
}
}

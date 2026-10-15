from time import sleep, perf_counter
from platform import system
import atexit
import sys
from yoke import events as EVENTS
from yoke.network import *
import struct
import re
from glob import glob
from threading import Thread
if system() == 'Windows':
    from yoke.vjoy.vjoydevice import VjoyDevice
elif system() == 'Linux':
    import uinput

ABS_EVENTS = [getattr(EVENTS, n) for n in dir(EVENTS) if n.startswith('ABS_')]
# Pedals and analog triggers rest at zero on the phone; every other axis rests at the center.
ABS_REST_AT_ZERO = [EVENTS.ABS_GAS, EVENTS.ABS_BRAKE, EVENTS.ABS_THROTTLE] + \
    [getattr(EVENTS, 'ABS_HAT{}{}'.format(i, a)) for i in range(4) for a in 'XY']

def abs_rest_value(event):
    return 0 if event in ABS_REST_AT_ZERO else 0x4000

# Basic error handlers used (and explained) by the script bin/yoke:
class TCPPortError(RuntimeError): pass
class DeviceNameTakenError(RuntimeError): pass
class UInputDisabledError(Exception): pass
class MalformedMessageError(Exception): pass

class Device:
    def __init__(self, id=1, name='Yoke', events=(), bytestring=b''):
        self.name = name + '-' + str(id)
        self.device = None
        for fn in glob('/sys/class/input/js*/device/name'):
            with open(fn) as f:
                fname = f.read().strip()
                if self.name == fname:
                    raise DeviceNameTakenError(self.name)

        # set range (0, 0x7fff) for abs events
        self.events = events
        self.bytestring = bytestring
        self.inStruct = struct.Struct('>x' + ''.join(['H' if e in ABS_EVENTS else '?' for e in events]))
        events = [e + (0, 0x7fff, 0, 0) if e in ABS_EVENTS else e for e in events]

        BUS_VIRTUAL = 0x06
        try:
            self.device = uinput.Device(events, self.name, BUS_VIRTUAL)
        except Exception as e:
            raise UInputDisabledError(*e.args)

        # New axes read as the minimum until the first status report arrives, which looks like
        # full deflection; start them at the value the phone sends at rest instead.
        rest = [(e, abs_rest_value(e)) for e in self.events if e in ABS_EVENTS]
        for e, v in rest:
            self.device.emit(e, v, False)
        if rest:
            self.device.syn()

    def emit(self, d, v):
        if d not in self.events:
            print('Event {} has not been registered… yet?'.format(d))
        self.device.emit(d, int(v), False)

    def flush(self):
        self.device.syn()

    def close(self):
        if self.device is not None:
            self.device.destroy()
            self.device = None
        self.bytestring = b''


# Override on Windows
if system() == 'Windows':
    class Device:
        def __init__(self, id=1, name='Yoke', events=(), bytestring=b''):
            super().__init__()
            self.name = name + '-' + str(id)
            self.device = VjoyDevice(id)
            self.lib = self.device.lib
            self.id = self.device.id
            self.inStruct = '>x'
            self.outStruct = self.device.outStruct
            self.events = []
            self.bytestring = bytestring
            #a vJoy controller has up to 8 axis with fixed names, and 128 buttons with no names.
            #TODO: Improve mapping between uinput events and vJoy controls.
            axes = 0
            buttons = 0
            for event in events:
                if event[0] == 0x01: # button/key
                    self.events.append((event[0], buttons)); buttons += 1
                    self.inStruct += '?'
                elif event[0] == 0x03: # analog axis
                    self.events.append((event[0], axes)); axes += 1
                    self.inStruct += 'H'
            self.inStruct = struct.Struct(self.inStruct)
            self.axes = [0,] * 15
            self.buttons = 0
            # Start axes at the phone's rest values instead of the minimum (see the Linux Device).
            axis_events = [e for e in events if e[0] == 0x03]
            for i, e in enumerate(axis_events[:len(self.axes)]):
                self.axes[i] = abs_rest_value(e) + 1
            if axis_events:
                self.flush()
        def emit(self, d, v):
            if d is not None:
                if d[0] == 0x03: #analog axis
                    # To map from [0x0, 0x7fff] to [0x1, 0x8000], just sum 1.
                    self.axes[d[1]] = v + 1
                else:
                    self.buttons |= (v << d[1])
        def flush(self):
            # Struct JOYSTICK_POSITION_V2's definition can be found at
            # https://github.com/shauleiz/vJoy/blob/2c9a6f14967083d29f5a294b8f5ac65d3d42ac87/SDK/inc/public.h#L203
            # It's basically:
            # 1 BYTE for device ID
            # 3 unused LONGs
            # 8 LONGs for axes
            # 7 unused LONGs
            # 1 LONGs for buttons
            # 4 DWORDs for hats
            # 3 LONGs for buttons
            self.lib.UpdateVJD(self.id, self.outStruct.pack(
                self.id, # 1 BYTE for device ID
                0, 0, 0, # 3 unused LONGs
                *self.axes, # 8 LONGs for axes and 7 unused LONGs
                self.buttons & 0xffffffff, # 1 LONG for buttons
                0, 0, 0, 0, # 4 DWORDs for hats
                (self.buttons >> 32) & 0xffffffff,
                (self.buttons >> 64) & 0xffffffff,
                (self.buttons >> 96) & 0xffffffff # 3 LONGs for buttons
            ))

            # This allows a very simple emit() definition:
            self.buttons = 0
        def close(self):
            self.device.close()
            self.bytestring = b''

class Service:
    sock = None
    discovery_sock = None
    info = None
    name = None
    devid = None
    # dt should be noticeably lower than the app's polling rate,
    # at least on Windows:
    dt = 0.006
    tdelta_max = 2

    def __init__(self, devname='Yoke', devid='1', iface='auto', port=0, bufsize=64, client_path=DEFAULT_CLIENT_PATH):
        self.dev = Device(devid, devname)
        self.name = devname
        self.devid = devid
        self.iface = iface
        self.port = port
        self.bufsize = bufsize
        self.client_path = client_path
        self.status_length = bufsize

    def preprocess(self, message):
        try:
            v = self.dev.inStruct.unpack(message)
        except struct.error:
            raise MalformedMessageError(len(message), self.dev.inStruct.size)
        return v

    def run(self):
        atexit.register(self.close_atexit)

        if self.iface == 'auto':
            self.iface = get_ip_address()

        # open udp socket on random available port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, self.bufsize)  # small buffer for low latency
        self.sock.bind((self.iface, self.port))
        self.sock.settimeout(0)
        adr, port = self.sock.getsockname()
        self.port = port

        check_webserver(self.client_path)
        self.thread = Thread(target=run_webserver, args=(self.port, self.client_path), daemon=True)
        self.thread.start()

        # create zeroconf service
        stype = '_yoke._udp.local.'
        netname = socket.gethostname() + '-' + self.dev.name
        fullname = netname + '.' + stype
        # Use a plain hostname as the SRV target; some Android mDNS resolvers fail on names like "x._yoke._udp.local.".
        server = re.sub('[^A-Za-z0-9-]', '-', netname) + '.local.'
        self.info = ServiceInfo(
            stype,
            fullname,
            addresses=[socket.inet_aton(adr)],
            port=port,
            properties={},
            server=server
        )
        zeroconf.register_service(self.info)

        self.discovery_sock = open_discovery_socket()
        if self.discovery_sock is not None:
            Thread(target=run_discovery_responder, args=(self.discovery_sock, netname, port), daemon=True).start()

        if not self.thread.is_alive():
            raise TCPPortError

        while True:
            trecv = perf_counter()
            irecv = 0
            connection = None
            print('\nTo connect select "{}" on your device,'.format(netname))
            print('or connect manually to "{}:{}"'.format(adr, port))
            print('Press Ctrl+C to exit.')

            while True:
                try:
                    m, address = self.sock.recvfrom(self.status_length)
                    if not m:
                        pass  # ignore empty datagrams
                    elif connection is None and m[0] != 255:
                        print('Connected to ', address)
                        connection = address

                    if m and connection == address:
                        trecv = perf_counter()
                        irecv = 0
                        # The first byte of a message tells us its general content:
                        # NULL BYTE: status report from the game controller.
                        if (m[0] == 0):
                            for ev, val in zip(self.dev.events, self.preprocess(m)):
                                self.dev.emit(ev, val)
                            self.dev.flush()
                        # 0xFF BYTE: request for disconnection. Same effect as a timeout.
                        elif (m[0] == 255):
                            print('Disconnected by request. Device destroyed.')
                            self.status_length = self.bufsize
                            self.dev.close()
                            break
                        # ANYTHING ELSE: information for a new layout.
                        # If there is no registered device, it registers a new device.
                        # If the device is already registered, check bytestrings. If they appear to match, ignore.
                        # If they don't match, print an error message and don't acknowledge.
                        else:
                            if self.dev.bytestring == b'':
                                try:
                                    codes = m.decode(encoding='UTF-8').split(',')
                                    # Expand aliases per code; substring replacement would turn e.g. 'b10' into 'BTN_GAMEPAD0'.
                                    v = ','.join(EVENTS.ALIAS.get(c, c) for c in codes).split(',')
                                    events = [getattr(EVENTS, n) for n in v]
                                    self.dev.close()
                                    self.dev = Device(self.devid, self.name, events, m)
                                    print('New control layout chosen.')
                                    self.status_length = self.dev.inStruct.size
                                    # HOTFIXES FOR WINDOWS.
                                    # Its websocket seem to lose packets on a predictable fashion, or create latency.
                                    # Until the root cause for this is found:
                                    if system() == 'Windows':
                                        # Windows doesn't acknowledge new layout registry messages after the first
                                        # for unclear reasons (possibly related to a mismatch between expected and
                                        # actual message length).
                                        # Until this is fixed, delay timeouts until receiving the first status report:
                                        trecv = trecv + 86400 # 24 hours will do.
                                except (AttributeError, UnicodeDecodeError):
                                    print('Error. Invalid layout discarded.')
                            else:
                                if not self.dev.bytestring.startswith(m):
                                    print('Error. Must unregister device before registering another.')

                    else:
                        pass  # ignore packets from other addresses

                except (socket.timeout, socket.error):
                    pass

                tdelta = perf_counter() - trecv

                if connection is not None and tdelta > self.tdelta_max:
                    print('Timeout ({} seconds), disconnected.'.format(self.tdelta_max))
                    print('  (listened {} times per second)'.format(int(irecv/tdelta)))
                    self.status_length = self.bufsize
                    if self.dev.bytestring != b'': self.dev.close()
                    break

                sleep(self.dt)
                irecv += 1

    def close_atexit(self):
        print('Yoke: Unregistering zeroconf service…')
        self.close()

    def close(self):
        atexit.unregister(self.close_atexit)
        if self.dev.bytestring != b'':
            self.dev.close()
        if self.sock is not None:
            self.sock.close()
        if self.discovery_sock is not None:
            self.discovery_sock.close()
            self.discovery_sock = None
        if self.info is not None:
            zeroconf.unregister_service(self.info)

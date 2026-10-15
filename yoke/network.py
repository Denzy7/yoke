from zeroconf import ServiceBrowser, Zeroconf, InterfaceChoice, ServiceInfo
import socket

zeroconf = Zeroconf()

# Webserver to serve files to android client
from http.server import HTTPServer, SimpleHTTPRequestHandler
import socketserver
import os, urllib, posixpath
import json

# TODO: These three lines allow using the syntax with socketserver with
# old versions of Python like in the now obsolete Debian 9 (Stretch).
# Please delete once socketserver.py is updated in every major Linux distro.

if not "__enter__" in dir(socketserver.BaseServer):
    socketserver.BaseServer.__enter__ = lambda self: self
    socketserver.BaseServer.__exit__ = lambda self, *args: self.server_close()

class HTTPRequestHandler(SimpleHTTPRequestHandler):
    basepath = os.getcwd()

    def translate_path(self, path):
        """Translate a /-separated PATH to the local filename syntax."""
        # abandon query parameters
        path = path.split('?',1)[0]
        path = path.split('#',1)[0]
        # Don't forget explicit trailing slash when normalizing. Issue17324
        trailing_slash = path.rstrip().endswith('/')
        try:
            path = urllib.parse.unquote(path, errors='surrogatepass')
        except UnicodeDecodeError:
            path = urllib.parse.unquote(path)
        path = posixpath.normpath(path)
        words = path.split('/')
        words = filter(None, words)
        path = self.basepath
        for word in words:
            if os.path.dirname(word) or word in (os.curdir, os.pardir):
                # Ignore components that are not a simple file/directory name
                continue
            path = os.path.join(path, word)
        if trailing_slash:
            path += '/'
        return path

def walk_failed(e):
    raise

def check_webserver(path):
    print('Checking files on webserver… ', end='')
    manifestContents = {
        'folders': [], 'files': [],
        'size': 0,
        'mtime': 0,
    }
    for root, dirs, files in os.walk(path, onerror=walk_failed):
        # If the folder separator is not a forward slash, convert it to a forward slash anyways.
        # It's what Android expects.
        if root != path:
            manifestContents['folders'].append(os.path.relpath(root, start=path).replace(os.sep, '/'))
        for entry in files:
            if entry != 'manifest.json':
                entrypath = os.path.join(root, entry)
                entrystat = os.stat(entrypath)
                manifestContents['files'].append(os.path.relpath(entrypath, start=path).replace(os.sep, '/'))
                manifestContents['size'] += entrystat.st_size
                manifestContents['mtime'] = max(manifestContents['mtime'], entrystat.st_mtime)
    print('OK.')
    try:
        print('Writing manifest… ', end='')
        with open(os.path.join(path, 'manifest.json'), 'w') as manifest:
            json.dump(manifestContents, manifest)
            print('OK.')
    except IOError:
        print('failed.\nYoke could not write a new `manifest.json` file to the webserver.\n'
            'You may play with an outdated file, but layouts downloaded from this server may be broken.')

def run_webserver(port, path):
    print('Starting webserver on ', port, path)
    class RH(HTTPRequestHandler):
        basepath = path
    try:
        with socketserver.TCPServer(('', port), RH) as httpd:
            httpd.serve_forever()
    except OSError:
        # No need for passing OSError to the main thread, just end this thread.
        # Service will verify the thread is closed and raise a TCPPortError.
        exit()

# Broadcast discovery, for networks that filter mDNS multicast.
# Must match DISCOVERY_PORT in the Android app (YokeActivity.java).
DISCOVERY_PORT = 15156

def open_discovery_socket():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # Let several yoke processes share the port; broadcasts are delivered to all of them.
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, 'SO_REUSEPORT'):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    try:
        s.bind(('', DISCOVERY_PORT))
    except OSError as err:
        s.close()
        print('Broadcast discovery unavailable ({}); the app can still find this machine via mDNS.'.format(err))
        return None
    return s

def run_discovery_responder(sock, name, port):
    # Request: b"YOKE?"  Reply: b"YOKE <port> <service name>"
    reply = 'YOKE {} {}'.format(port, name).encode('UTF-8')
    while True:
        try:
            m, address = sock.recvfrom(64)
            if m == b'YOKE?':
                sock.sendto(reply, address)
        except OSError:
            return  # socket closed by Service.close()

DEFAULT_CLIENT_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'yoke', 'assets', 'joypad')

def get_ip_address():
    # Connecting a UDP socket sends no packets; it only picks the interface with a route to that address.
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        return s.getsockname()[0]
    except OSError:
        # No default route (offline LAN, USB/Bluetooth tethering): fall back to the hostname's address.
        ip = socket.gethostbyname(socket.gethostname())
        if ip.startswith('127.'):
            raise OSError('Could not detect a network interface. Select one with --iface IP.')
        return ip
    finally:
        s.close()

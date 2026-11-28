# Opt-in loopback fixture for manual_ssh_service_test.dart. No host config.
import base64
import hashlib
import json
import socket
import subprocess
import sys
import threading
import time

import paramiko


def emit(**event):
    print(json.dumps(event), flush=True)


class FixtureServer(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return 'password'

    def check_auth_password(self, username, password):
        emit(event='auth')
        return (paramiko.AUTH_SUCCESSFUL
                if (username, password) == ('fixture', 'test-only-password')
                else paramiko.AUTH_FAILED)

    def check_channel_request(self, kind, channel_id):
        return (paramiko.OPEN_SUCCEEDED if kind == 'session'
                else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED)

    def check_channel_exec_request(self, channel, command):
        programs = {
            b'fixture-small': "import sys;print('local-ssh');print('diagnostic',file=sys.stderr);sys.exit(7)",
            b'fixture-large': "import sys;sys.stderr.write('e'*80000);sys.stdout.write('o'*80000)",
            b'fixture-hold': None,
        }
        if command not in programs:
            return False
        threading.Thread(target=self.run, args=(channel, programs[command]), daemon=True).start()
        return True

    @staticmethod
    def run(channel, program):
        emit(event='exec')
        try:
            if program is None:
                time.sleep(5)
                return
            result = subprocess.run([sys.executable, '-I', '-c', program],
                                    capture_output=True, check=False, timeout=3)
            channel.sendall_stderr(result.stderr)
            channel.sendall(result.stdout)
            channel.send_exit_status(result.returncode)
        except OSError:
            pass
        finally:
            channel.close()


def serve(peer, key):
    transport = paramiko.Transport(peer)
    transport.add_server_key(key)
    try:
        transport.start_server(server=FixtureServer())
        channels = []
        while transport.is_active():
            channel = transport.accept(.1)
            if channel is not None:
                channels.append(channel)
    except (EOFError, OSError, paramiko.SSHException):
        pass
    finally:
        transport.close()
        emit(event='closed')


def main():
    key = paramiko.RSAKey.generate(2048)
    fingerprint = base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0))
        server.listen()
        emit(event='ready', port=server.getsockname()[1], fingerprint='SHA256:' + fingerprint)
        while True:
            peer, _ = server.accept()
            threading.Thread(target=serve, args=(peer, key), daemon=True).start()


if __name__ == '__main__':
    main()

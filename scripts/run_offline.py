"""Execute a local x86-64 Linux verification command with network sockets denied."""
import ctypes
import errno
import os
import platform
import sys


def main():
    if platform.system() != 'Linux' or platform.machine() != 'x86_64' or len(sys.argv) < 2:
        raise SystemExit('requires Linux x86_64 and a command')
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte), ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    # Verify architecture before interpreting syscall numbers; deny socket creation
    # and network operations. Pipes/stdout and ordinary local file reads still work.
    entries = [Filter(0x20, 0, 0, 4), Filter(0x15, 1, 0, 0xc000003e), Filter(0x06, 0, 0, 0x80000000),
               Filter(0x20, 0, 0, 0)]
    for number in [41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,288,299,307]:
        entries += [Filter(0x15, 0, 1, number), Filter(0x06, 0, 0, 0x00050000 | errno.EPERM)]
    entries.append(Filter(0x06, 0, 0, 0x7fff0000))
    filters = (Filter * len(entries))(*entries)
    program = Program(len(entries), filters)
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) or libc.prctl(22, 2, ctypes.byref(program), 0, 0):
        raise OSError(ctypes.get_errno(), 'cannot install offline verification filter')
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == '__main__':
    main()

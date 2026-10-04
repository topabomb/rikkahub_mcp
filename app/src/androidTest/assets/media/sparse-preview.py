"""Create an exclusive sparse MP4 fixture in a test-owned workspace directory."""
import pathlib
import struct
import sys

source, target = map(pathlib.Path, sys.argv[1:])
assert source.parent == target.parent and source.parent.name.startswith('android-preview-')
original = source.read_bytes()

def boxes(data, start, end):
    while start < end:
        size, kind = struct.unpack_from('>I4s', data, start)
        assert size >= 8 and start + size <= end
        yield start, size, kind
        start += size
    assert start == end

top = {kind: (offset, size) for offset, size, kind in boxes(original, 0, len(original))}
moov, moov_size = top[b'moov']
mdat, mdat_size = top[b'mdat']
metadata = bytearray(original[moov:moov + moov_size])
media_start = 3 * 1024**3

def patch(start, end):
    for offset, size, kind in boxes(metadata, start, end):
        if kind == b'stco':
            count, = struct.unpack_from('>I', metadata, offset + 12)
            assert offset + 16 + 4 * count <= offset + size
            for index in range(count):
                at = offset + 16 + 4 * index
                previous, = struct.unpack_from('>I', metadata, at)
                struct.pack_into('>I', metadata, at, previous + media_start - mdat)
        elif kind in (b'moov', b'trak', b'mdia', b'minf', b'stbl'):
            patch(offset + 8, offset + size)

patch(0, len(metadata))
with target.open('xb') as output:
    output.truncate(4 * 1024**3)
    ftyp, ftyp_size = top[b'ftyp']
    output.write(original[ftyp:ftyp + ftyp_size])
    output.write(metadata)
    output.write(struct.pack('>I4s', media_start - output.tell(), b'free'))
    output.seek(media_start)
    output.write(original[mdat:mdat + mdat_size])
    output.write(struct.pack('>I4s', 4 * 1024**3 - output.tell(), b'free'))
print('SPARSE_PREVIEW_READY', target.stat().st_size)

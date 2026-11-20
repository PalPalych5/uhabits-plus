import zlib
import struct
from pathlib import Path

def read_png(path):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', "Invalid PNG signature"

    pos = 8
    width = height = bit_depth = color_type = None
    idat_chunks = []

    while pos < len(data):
        length, chunk_type = struct.unpack('>I4s', data[pos:pos+8])
        pos += 8
        chunk_data = data[pos:pos+length]
        pos += length + 4 # skip CRC

        if chunk_type == b'IHDR':
            width, height, bit_depth, color_type, comp, filt, inter = struct.unpack('>IIBBBBB', chunk_data)
        elif chunk_type == b'IDAT':
            idat_chunks.append(chunk_data)
        elif chunk_type == b'IEND':
            break

    decompressed = zlib.decompress(b''.join(idat_chunks))

    # Assuming RGBA (color_type 6, bit_depth 8) or RGB (color_type 2)
    bpp = 4 if color_type == 6 else (3 if color_type == 2 else 1)
    stride = width * bpp

    pixels = bytearray(width * height * bpp)

    src_pos = 0
    prev_row = bytearray(stride)

    for y in range(height):
        filter_type = decompressed[src_pos]
        src_pos += 1
        raw_row = bytearray(decompressed[src_pos:src_pos+stride])
        src_pos += stride

        # Apply PNG defilter
        recon_row = bytearray(stride)
        for x in range(stride):
            raw = raw_row[x]
            a = recon_row[x - bpp] if x >= bpp else 0
            b = prev_row[x]
            c = prev_row[x - bpp] if x >= bpp else 0

            if filter_type == 0: # None
                recon = raw
            elif filter_type == 1: # Sub
                recon = (raw + a) & 0xff
            elif filter_type == 2: # Up
                recon = (raw + b) & 0xff
            elif filter_type == 3: # Average
                recon = (raw + ((a + b) // 2)) & 0xff
            elif filter_type == 4: # Paeth
                p = a + b - c
                pa = abs(p - a)
                pb = abs(p - b)
                pc = abs(p - c)
                if pa <= pb and pa <= pc:
                    pr = a
                elif pb <= pc:
                    pr = b
                else:
                    pr = c
                recon = (raw + pr) & 0xff
            else:
                raise ValueError(f"Unknown filter: {filter_type}")
            recon_row[x] = recon

        pixels[y*stride:(y+1)*stride] = recon_row
        prev_row = recon_row

    return {
        'width': width,
        'height': height,
        'bpp': bpp,
        'pixels': list(pixels)
    }

def compare_images(p1, p2, tolerance=0):
    img1 = read_png(p1)
    img2 = read_png(p2)

    assert img1['width'] == img2['width'] and img1['height'] == img2['height'], "Dimension mismatch"
    w, h, bpp = img1['width'], img1['height'], img1['bpp']
    total_pixels = w * h

    diff_pixels = 0
    max_diff = 0
    total_abs_diff = 0

    pix1 = img1['pixels']
    pix2 = img2['pixels']

    for i in range(0, len(pix1), bpp):
        diff = max(abs(pix1[i+c] - pix2[i+c]) for c in range(bpp))
        if diff > tolerance:
            diff_pixels += 1
            max_diff = max(max_diff, diff)
            total_abs_diff += diff

    diff_pct = (diff_pixels / total_pixels) * 100
    mean_error = total_abs_diff / (total_pixels * 255.0 * bpp)
    return {
        'total_pixels': total_pixels,
        'diff_pixels': diff_pixels,
        'diff_pct': diff_pct,
        'max_diff': max_diff,
        'mean_error': mean_error
    }

if __name__ == '__main__':
    orig = 'tools/qa/test_icon_24.png'
    fixed = 'tools/qa/test_fixed_24.png'
    broken = 'tools/qa/test_broken_24.png'

    print("Comparing Original SVG vs Properly Converted VectorDrawable (Fixed):")
    res_fixed = compare_images(orig, fixed)
    print(f"  Diff pixels: {res_fixed['diff_pixels']}/{res_fixed['total_pixels']} ({res_fixed['diff_pct']:.2f}%)")
    print(f"  Max channel diff: {res_fixed['max_diff']}")

    print("\nComparing Original SVG vs Broken VectorDrawable (Naive 'm'->'M'):")
    res_broken = compare_images(orig, broken)
    print(f"  Diff pixels: {res_broken['diff_pixels']}/{res_broken['total_pixels']} ({res_broken['diff_pct']:.2f}%)")
    print(f"  Max channel diff: {res_broken['max_diff']}")

#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 hirero-exists
# SPDX-License-Identifier: Apache-2.0
#

"""Export ROW/PRC vendor_boot images and matching signed vbmeta images."""

import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def root_properties(dtb):
    header = struct.unpack_from('>10I', dtb)
    magic, size, structure, strings, _, version, _, _, strings_size, structure_size = header
    require(magic == 0xd00dfeed and size == len(dtb) and version == 17,
            'Unsupported DTB header')
    require(40 <= structure < structure + structure_size <= size and
            40 <= strings < strings + strings_size <= size, 'Invalid DTB bounds')
    properties = {}
    cursor, depth = structure, 0
    while cursor < structure + structure_size:
        token = struct.unpack_from('>I', dtb, cursor)[0]
        cursor += 4
        if token == 1:
            end = dtb.index(0, cursor, structure + structure_size)
            depth += 1
            cursor = (end + 4) & ~3
        elif token == 2:
            depth -= 1
            require(depth >= 0, 'Unbalanced DTB nodes')
        elif token == 3:
            length, name_offset = struct.unpack_from('>2I', dtb, cursor)
            cursor += 8
            require(0 <= name_offset < strings_size and
                    cursor + length <= structure + structure_size, 'Invalid DTB property')
            name_start = strings + name_offset
            end = dtb.index(0, name_start, strings + strings_size)
            name = dtb[name_start:end].decode('ascii')
            if depth == 1:
                require(name not in properties, 'Duplicate root property: ' + name)
                properties[name] = (cursor, dtb[cursor:cursor + length])
            cursor = (cursor + length + 3) & ~3
        elif token == 4:
            pass
        elif token == 9:
            require(depth == 0, 'Unbalanced DTB end')
            return properties
        else:
            raise ValueError('Unsupported DTB token')
    raise ValueError('Missing DTB end')


def patch_region(data):
    require(data[:8] == b'VNDRBOOT', 'Not a vendor boot image')
    version, page, _, _, ramdisk_size = struct.unpack_from('<5I', data, 8)
    header_size, dtb_size = struct.unpack_from('<2I', data, 2096)
    require(version == 4 and page == 4096 and header_size == 2128,
            'Unsupported vendor boot layout')
    offset = ((header_size + page - 1) // page +
              (ramdisk_size + page - 1) // page) * page
    end = offset + dtb_size
    require(end <= len(data), 'Truncated DTB section')
    patched = bytearray(data)
    changes, count = [], 0
    expected = {(b'Qualcomm Technologies, Inc. Tuna SoC\0', b'qcom,tuna\0'),
                (b'Qualcomm Technologies, Inc. Tuna 7 SoC\0', b'qcom,tuna\0'),
                (b'Qualcomm Technologies, Inc. TunaP SoC\0', b'qcom,tunap\0')}
    found = set()
    while offset < end:
        magic, size = struct.unpack_from('>2I', data, offset)
        require(magic == 0xd00dfeed and 40 <= size <= end - offset,
                'Invalid concatenated DTB')
        properties = root_properties(data[offset:offset + size])
        if 'region,country' in properties:
            compatible = properties['compatible'][1]
            model = properties['model'][1]
            identity = (model, compatible)
            location, value = properties['region,country']
            require(identity in expected and identity not in found and value == b'ROW\0',
                    'Unexpected regional DTB; refusing an unreviewed patch')
            found.add(identity)
            absolute = offset + location
            patched[absolute:absolute + 4] = b'PRC\0'
            changes.append({'model': model.rstrip(b'\0').decode(),
                            'compatible': compatible.rstrip(b'\0').decode(),
                            'offset': absolute, 'from': 'ROW', 'to': 'PRC'})
        offset += size
        count += 1
    require(offset == end and count == 19 and found == expected,
            'Expected all 19 DTBs and exactly three regional Tuna DTBs')
    require(sum(a != b for a, b in zip(data, patched)) == 9,
            'Regional payload change exceeds nine bytes')
    return patched, changes


def public_key(header, blob):
    start = header.SIZE + header.authentication_data_block_size + header.public_key_offset
    return blob[start:start + header.public_key_size]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product-out', type=Path, required=True)
    parser.add_argument('--target-files', type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[4]
    target = args.target_files or args.product_out / 'obj/PACKAGING/target_files_intermediates/aospa_malbec-target_files'
    images = target / 'IMAGES'
    info = dict(line.split('=', 1) for line in (target / 'META/misc_info.txt').read_text().splitlines()
                if '=' in line)
    spec = importlib.util.spec_from_file_location('avbtool', source / 'external/avb/avbtool.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    avb = module.Avb()

    def parse(path):
        image = module.ImageHandler(str(path), read_only=True)
        footer, header, descriptors, _ = avb._parse_image(image)
        blob = avb._load_vbmeta_blob(image)
        require(module.verify_vbmeta_signature(header, blob), 'Invalid AVB signature')
        return footer, header, descriptors, blob

    original = (images / 'vendor_boot.img').read_bytes()
    footer, header, descriptors, blob = parse(images / 'vendor_boot.img')
    require(footer is not None and header.algorithm_type == 0,
            'Expected vendor_boot hash footer authenticated by root vbmeta')
    hashes = [d for d in descriptors if isinstance(d, module.AvbHashDescriptor)]
    require(len(hashes) == 1 and hashes[0].partition_name == 'vendor_boot',
            'Unexpected vendor_boot hash descriptors')
    old_hash = hashes[0]
    require(old_hash.image_size == footer.original_image_size and
            hashlib.new(old_hash.hash_algorithm,
                        old_hash.salt + original[:old_hash.image_size]).digest() == old_hash.digest,
            'Source vendor_boot digest mismatch')
    patched, changes = patch_region(original[:footer.original_image_size])
    new_hash = copy.deepcopy(old_hash)
    new_hash.digest = hashlib.new(old_hash.hash_algorithm, old_hash.salt + patched).digest()
    require(blob.count(old_hash.encode()) == 1, 'Ambiguous AVB hash descriptor')
    new_blob = blob.replace(old_hash.encode(), new_hash.encode())
    regional = bytearray(original)
    regional[:len(patched)] = patched
    regional[footer.vbmeta_offset:footer.vbmeta_offset + len(new_blob)] = new_blob

    _, root_header, root_descriptors, root_blob = parse(images / 'vbmeta.img')
    require(root_header.algorithm_type != 0 and root_header.public_key_metadata_size == 0,
            'Expected the configured signed root vbmeta without opaque key metadata')
    root_hashes = [d for d in root_descriptors if isinstance(d, module.AvbHashDescriptor)
                   and d.partition_name == 'vendor_boot']
    require(len(root_hashes) == 1 and root_hashes[0].encode() == old_hash.encode(),
            'Root vbmeta does not match source vendor_boot')
    key = Path(info['avb_vbmeta_key_path'])
    if not key.is_absolute():
        key = source / key
    require(module.RSAPublicKey.load(str(key)).encode() == public_key(root_header, root_blob),
            'Configured signing identity differs from source vbmeta')
    regional_descriptors = [new_hash if d is root_hashes[0] else d for d in root_descriptors]
    algorithm, _ = module.lookup_algorithm_by_type(root_header.algorithm_type)
    signed = avb._generate_vbmeta_blob(
        algorithm_name=algorithm, key_path=str(key), public_key_metadata_path=None,
        descriptors=regional_descriptors, chain_partitions_use_ab=None,
        chain_partitions_do_not_use_ab=None, rollback_index=root_header.rollback_index,
        flags=root_header.flags, rollback_index_location=root_header.rollback_index_location,
        props=None, props_from_file=None, kernel_cmdlines=None, setup_rootfs_from_kernel=None,
        ht_desc_to_setup=None, include_descriptors_from_image=None, signing_helper=None,
        signing_helper_with_files=None, release_string=root_header.release_string,
        append_to_release_string=None,
        required_libavb_version_minor=root_header.required_libavb_version_minor)
    root_original = (images / 'vbmeta.img').read_bytes()
    require(len(signed) <= len(root_original), 'Regional vbmeta exceeds source image size')
    signed += b'\0' * (len(root_original) - len(signed))

    outputs = {'vendor_boot.img': original, 'vendor_boot_TB390FU.img': original,
               'vendor_boot_TB376FC.img': regional, 'vbmeta_TB390FU.img': root_original,
               'vbmeta_TB376FC.img': signed}
    with tempfile.TemporaryDirectory(prefix='.malbec-regional-', dir=args.product_out) as temporary:
        staging = Path(temporary)
        for name, data in outputs.items():
            (staging / name).write_bytes(data)
        _, cn_header, cn_descriptors, _ = parse(staging / 'vendor_boot_TB376FC.img')
        require(cn_header.encode() == header.encode() and
                [d.encode() for d in cn_descriptors] ==
                [new_hash.encode() if d is old_hash else d.encode() for d in descriptors],
                'Unexpected vendor_boot AVB metadata change')
        _, cn_root_header, cn_root_descriptors, cn_root_blob = parse(staging / 'vbmeta_TB376FC.img')
        require(cn_root_header.algorithm_type == root_header.algorithm_type and
                cn_root_header.release_string == root_header.release_string and
                cn_root_header.required_libavb_version_major == root_header.required_libavb_version_major and
                cn_root_header.required_libavb_version_minor == root_header.required_libavb_version_minor and
                cn_root_header.flags == root_header.flags and
                cn_root_header.rollback_index == root_header.rollback_index and
                cn_root_header.rollback_index_location == root_header.rollback_index_location and
                public_key(cn_root_header, cn_root_blob) == public_key(root_header, root_blob) and
                [d.encode() for d in cn_root_descriptors] == [d.encode() for d in regional_descriptors],
                'Unexpected root vbmeta identity, rollback or descriptor change')
        report = {'source_vendor_boot_sha256': sha(original), 'source_vbmeta_sha256': sha(root_original),
                  'regions': changes, 'payload_changed_bytes': 9, 'dtbs': 19,
                  'ramdisk_bootconfig_and_other_dtbs_unchanged': True,
                  'avb_signatures_and_descriptors_verified': True, 'avb_flags': root_header.flags,
                  'files': {name: {'bytes': len(data), 'sha256': sha(data)} for name, data in outputs.items()},
                  'note': 'TB376FC uses the matching regional vbmeta. The ordinary OTA still contains ROW vendor_boot; these separate exports do not create a PRC OTA. Device boot testing remains required.',
                  'prior_art': 'https://github.com/suddenBook/android_device_lenovo_malbec/releases/tag/20260816'}
        (staging / 'regional-vendor-boot.json').write_text(json.dumps(report, indent=2) + '\n')
        for name, data in outputs.items():
            (staging / (name + '.sha256')).write_text(sha(data) + '  ' + name + '\n')
        manifest = 'regional-vendor-boot.json'
        (args.product_out / manifest).unlink(missing_ok=True)
        for path in staging.iterdir():
            if path.name != manifest:
                os.replace(path, args.product_out / path.name)
        os.replace(staging / manifest, args.product_out / manifest)
    print('Exported TB390FU/ROW and TB376FC/PRC vendor_boot with matching AVB metadata.')


if __name__ == '__main__':
    main()

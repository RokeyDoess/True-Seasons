"""Read a spark SamplerData protobuf with standard-library-only, bounded wire decoding.

Schema: https://github.com/lucko/spark/blob/master/spark-common/src/main/proto/spark/spark_sampler.proto
Profile data is never executed and private server configurations are not exported.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
import hashlib
from pathlib import Path
import struct


def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ValueError("Truncated protobuf varint")
        part = data[offset]
        offset += 1
        value |= (part & 127) << shift
        if not part & 128:
            return value, offset
    raise ValueError("Oversized protobuf varint")


def message(data):
    fields = defaultdict(list)
    offset = 0
    while offset < len(data):
        tag, offset = varint(data, offset)
        field, wire = tag >> 3, tag & 7
        if field == 0:
            raise ValueError("Invalid protobuf field")
        if wire == 0:
            value, offset = varint(data, offset)
        else:
            if wire == 1:
                size = 8
            elif wire == 5:
                size = 4
            elif wire == 2:
                size, offset = varint(data, offset)
            else:
                raise ValueError(f"Unsupported protobuf wire {wire}")
            if size > len(data) - offset:
                raise ValueError("Truncated protobuf field")
            value = data[offset:offset + size]
            offset += size
        fields[field].append((wire, value))
    return fields


def first(fields, field, default=None):
    return fields[field][0][1] if fields[field] else default


def text(fields, field):
    return first(fields, field, b"").decode("utf-8", errors="replace")


def doubles(fields, field):
    values = []
    for wire, value in fields[field]:
        if wire == 1:
            values.append(struct.unpack("<d", value)[0])
        elif wire == 2:
            if len(value) % 8:
                raise ValueError("Malformed packed doubles")
            values.extend(struct.unpack("<" + "d" * (len(value) // 8), value))
    return values


def integers(fields, field):
    values = []
    for wire, value in fields[field]:
        if wire == 0:
            values.append(value)
        elif wire == 2:
            offset = 0
            while offset < len(value):
                item, offset = varint(value, offset)
                values.append(item)
    return values


def nested(fields, field):
    return message(first(fields, field, b""))


def stack_node(data):
    fields = message(data)
    return {
        "class": text(fields, 3), "method": text(fields, 4),
        "line": first(fields, 6, 0), "caller_line": first(fields, 5, 0),
        "descriptor": text(fields, 7), "times_ms": doubles(fields, 8),
        "children": integers(fields, 9),
    }


def summarize_thread(data):
    fields = message(data)
    nodes = [stack_node(value) for _, value in fields[3]]
    roots = integers(fields, 5)
    times = doubles(fields, 4)
    total = sum(times)
    methods = defaultdict(lambda: {"inclusive_ms": 0, "self_ms": 0, "lines": set()})
    paths = []
    plugin_time = 0
    reachable = set()

    def visit(index, path, seen, inside_plugin):
        nonlocal plugin_time
        if index in seen or not 0 <= index < len(nodes):
            raise ValueError("Invalid/cyclic stack references")
        node = nodes[index]
        reachable.add(index)
        duration = sum(node["times_ms"])
        children_time = sum(sum(nodes[child]["times_ms"]) for child in node["children"])
        key = node["class"] + "." + node["method"]
        methods[key]["inclusive_ms"] += duration
        methods[key]["self_ms"] += max(0, duration - children_time)
        methods[key]["lines"].add(node["line"])
        current_path = path + [f"{key}:{node['line']}"]
        plugin = node["class"].startswith("dev.trueseasons.")
        if plugin and not inside_plugin:
            plugin_time += duration
        if plugin:
            paths.append({"time_ms": duration, "percent": 100 * duration / total if total else 0, "path": current_path,
                          "children": [{"method": nodes[c]["class"] + "." + nodes[c]["method"],
                                        "time_ms": sum(nodes[c]["times_ms"])} for c in node["children"]]})
        for child in node["children"]:
            visit(child, current_path, seen | {index}, inside_plugin or plugin)

    for root in roots:
        visit(root, [], set(), False)
    rows = []
    for key, value in methods.items():
        rows.append({"method": key, "inclusive_ms": value["inclusive_ms"], "self_ms": value["self_ms"],
                     "inclusive_percent": 100 * value["inclusive_ms"] / total if total else 0,
                     "self_percent": 100 * value["self_ms"] / total if total else 0,
                     "lines": sorted(value["lines"])})
    return {"name": text(fields, 1), "sampled_ms": total, "times_ms": times, "node_count": len(nodes),
            "reachable_nodes": len(reachable), "root_sum_ms": sum(sum(nodes[r]["times_ms"]) for r in roots),
            "plugin_ms": plugin_time, "plugin_percent": 100 * plugin_time / total if total else 0,
            "methods": sorted(rows, key=lambda row: row["inclusive_ms"], reverse=True),
            "plugin_paths": sorted(paths, key=lambda row: row["time_ms"], reverse=True)}


def analyze(profile):
    encoded = profile.read_bytes()
    fields = message(encoded)
    metadata = nested(fields, 1)
    platform = nested(metadata, 7)
    stats = nested(metadata, 8)
    tps = nested(stats, 4)
    mspt = nested(stats, 5)
    mspt_one = nested(mspt, 1)
    start, end = first(metadata, 2, 0), first(metadata, 11, 0)
    sources = {}
    for _, value in metadata[13]:
        entry = message(value)
        source = nested(entry, 2)
        if "season" in text(source, 1).lower() or "blue" in text(source, 1).lower():
            sources[text(entry, 1)] = {"name": text(source, 1), "version": text(source, 2)}
    windows = []
    for _, value in fields[7]:
        entry = message(value)
        window = nested(entry, 2)
        windows.append({"index": first(entry, 1, 0), "ticks": first(window, 1, 0),
                        "tps": sum(doubles(window, 4)), "mspt_median": sum(doubles(window, 5)),
                        "mspt_max": sum(doubles(window, 6)), "chunks": first(window, 10, 0),
                        "players": first(window, 7, 0), "duration_ms": first(window, 13, 0)})
    return {"profile": str(profile), "bytes": profile.stat().st_size, "sha256": hashlib.sha256(encoded).hexdigest(),
            "start_utc": datetime.fromtimestamp(start / 1000, timezone.utc).isoformat() if start else None,
            "end_utc": datetime.fromtimestamp(end / 1000, timezone.utc).isoformat() if end else None,
            "elapsed_ms": end - start if end else None, "sample_interval_microseconds": first(metadata, 3, 0),
            "included_ticks": first(metadata, 12, 0), "sampler_mode": first(metadata, 15, 0),
            "sampler_engine": first(metadata, 16, 0), "engine_version": text(metadata, 17),
            "platform": {"name": text(platform, 2), "version": text(platform, 3), "minecraft": text(platform, 4),
                         "spark_version": text(platform, 9)}, "sources": sources,
            "tps": {"last1m": sum(doubles(tps, 1)), "last5m": sum(doubles(tps, 2)), "last15m": sum(doubles(tps, 3))},
            "mspt_last1m": {"mean": sum(doubles(mspt_one, 1)), "maximum": sum(doubles(mspt_one, 2)),
                            "median": sum(doubles(mspt_one, 4)), "p95": sum(doubles(mspt_one, 5))},
            "windows": windows, "threads": [summarize_thread(value) for _, value in fields[2]]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = analyze(args.profile)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "threads"}, indent=2))
    for thread in report["threads"]:
        print(f"THREAD {thread['name']}: {thread['sampled_ms']:.0f} ms; TrueSeasons {thread['plugin_percent']:.2f}%; {thread['node_count']} nodes")
        if thread["name"] == "Server thread":
            print("MAIN HOT METHODS (inclusive / self):")
            for row in thread["methods"][:30]:
                print(f"{row['inclusive_percent']:7.2f}% / {row['self_percent']:7.2f}% {row['method']}")
            print("MAIN TRUESEASONS METHODS:")
            for row in thread["methods"]:
                if row["method"].startswith("dev.trueseasons."):
                    print(f"{row['inclusive_percent']:7.2f}% / {row['self_percent']:7.2f}% {row['method']} lines={row['lines']}")

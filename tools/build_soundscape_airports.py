"""Builds app/src/main/assets/soundscape/airports.tsv for the soundscape (classes/soundscape/Airports):
the box dbs carry no aeroway data, so airliners overhead and each point's country come from this table.

Source: OurAirports, public domain, https://davidmegginson.github.io/ourairports-data/airports.csv
Kept: large airports, and medium ones with scheduled service (small strips see no airliners).

    python tools/build_soundscape_airports.py path/to/airports.csv
"""
import csv
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "soundscape", "airports.tsv")


def main():
    rows = []
    with open(sys.argv[1], encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if r["type"] == "large_airport" or (r["type"] == "medium_airport" and r["scheduled_service"] == "yes"):
                rows.append((float(r["latitude_deg"]), float(r["longitude_deg"]), r["iso_country"]))
    rows.sort()
    with open(OUT, "w", encoding="ascii", newline="\n") as f:
        for lat, lon, iso in rows:
            f.write(f"{lat:.3f}\t{lon:.3f}\t{iso}\n")
    print(len(rows), "airports,", os.path.getsize(OUT) // 1024, "KB")


if __name__ == "__main__":
    main()

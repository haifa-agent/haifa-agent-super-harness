"""Synthetic bounded package for real UI upload/export; no package code executes."""
import argparse
import io
import json
import sys
import zipfile

NAME = "w15-package-ui"
FILES = {
    "SKILL.md": b"---\nname: w15-package-ui\ndescription: A controlled UI package for acceptance\nlicense: MIT\n---\nW15_CONTROLLED_UI_PACKAGE_INSTRUCTIONS\nRead references/fact.txt before answering.\n",
    "references/fact.txt": b"W15_CONTROLLED_UI_PACKAGE_FACT=17\n",
    "evals/evals.json": b'{"skill_name":"w15-package-ui","evals":[{"id":1,"prompt":"Read the supplied fact","expected_output":"17","files":[]}]}'
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify")
    args = parser.parse_args()
    if args.verify:
        with zipfile.ZipFile(args.verify) as archive:
            restored = {name.removeprefix(NAME + "/"): archive.read(name) for name in archive.namelist() if not name.endswith("/")}
        if restored != FILES:
            raise SystemExit("Exported complete resource bytes differ from the uploaded fixture")
        print(json.dumps({"status": "PASS", "files": sorted(FILES), "bytes": sum(map(len, FILES.values()))}))
    else:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in sorted(FILES.items()):
                archive.writestr(NAME + "/" + name, content)
        sys.stdout.buffer.write(output.getvalue())

if __name__ == "__main__":
    main()

"""Read-only evidence collection and a fresh-output offline build. Run from repository root."""
import csv, hashlib, json, os, shutil, subprocess, sys, tempfile, time, xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime, timezone
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "statics/results"
OUT.mkdir(parents=True, exist_ok=True)
WORK = ROOT / "runtime"
WORK.mkdir(exist_ok=True)
stage = Path(tempfile.mkdtemp(prefix="release-check-", dir=WORK))
for folder in ("source", "scripts"):
    shutil.copytree(ROOT/folder, stage/folder, ignore=shutil.ignore_patterns("target", "__pycache__"))
for f in ROOT.glob("*.ps1"):
    shutil.copy2(f, stage/f.name)
java_version = subprocess.run(["java", "-version"],capture_output=True,text=True,errors="replace").stderr
evidence = {"timestamp": datetime.now(timezone.utc).isoformat(), "freshCheckout": str(stage.relative_to(ROOT)),
            "network": "LOCALHOST", "javaVersion": java_version,
            "java21Runtime": "Current java reports 21" if 'version "21.' in java_version else "NOT_TESTED",
            "checks": [], "instructionSha256": hashlib.sha256((ROOT/"INSTRUCTION.md").read_bytes()).hexdigest()}
def run(name, args, cwd=stage, timeout=300):
    log = WORK/(name+".log")
    start = time.monotonic()
    with log.open("wb") as stream:
        result = subprocess.run(args, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    text = log.read_bytes().decode("utf-8", "replace")
    check = {"name": name, "command": args, "exitCode": result.returncode,
             "elapsedSeconds": time.monotonic()-start, "log": str(log.relative_to(ROOT))}
    evidence["checks"].append(check)
    print(name, result.returncode, flush=True)
    return result.returncode, text
maven = shutil.which("mvn.cmd") or shutil.which("mvn")
cache = os.path.relpath(ROOT/".m2/repository", stage/"source")
run("phase10-maven-clean", [maven, "-o", "-Dmaven.repo.local="+cache, "clean"], cwd=stage/"source")
code, text = run("phase10-fresh-package", [maven, "-o", "-Dmaven.repo.local="+cache, "package"], cwd=stage/"source")
cases = []
for report in sorted((stage/"source").glob("*/target/surefire-reports/TEST-*.xml")):
    for case in ET.parse(report).getroot().findall("testcase"):
        issue = next((case.find(t) for t in ("failure","error","skipped") if case.find(t) is not None), None)
        status = "PASS" if issue is None else "BLOCKED" if issue.tag=="skipped" else "FAIL"
        cases.append({"module": report.parents[2].name, "test": case.get("classname")+"."+case.get("name"),
                      "status": status, "elapsedSeconds": case.get("time"), "notes": "" if issue is None else issue.get("message","")})
if cases:
    with (OUT/"phase10-junit.csv").open("w", encoding="utf-8", newline="") as stream:
        writer=csv.DictWriter(stream,fieldnames=cases[0]); writer.writeheader(); writer.writerows(cases)
evidence["tests"]={s:sum(c["status"]==s for c in cases) for s in ("PASS","FAIL","BLOCKED")}
classes=list((stage/"source").glob("*/target/classes/**/*.class"))
evidence["bytecodeMajorVersions"]=sorted(set(int.from_bytes(f.read_bytes()[6:8],"big") for f in classes))
evidence["packagedModules"]=[p.parent.parent.name for p in (stage/"source").glob("*/target/*-SNAPSHOT.jar")]
if code==0:
    # Launchers use the same relative cache layout as build.ps1. Copy only their three runtime dependencies.
    for artifact in ("jackson-databind","jackson-core","jackson-annotations"):
        rel=Path("com/fasterxml/jackson/core")/artifact/"2.18.3"/(artifact+"-2.18.3.jar")
        dst=stage/".m2/repository"/rel; dst.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(ROOT/".m2/repository"/rel,dst)
    import socket, re
    def port(kind):
        with socket.socket(socket.AF_INET,kind) as s:
            s.bind(("127.0.0.1",0)); return s.getsockname()[1]
    tcp,udp=port(socket.SOCK_STREAM),port(socket.SOCK_DGRAM)
    ps=["powershell.exe","-NoProfile","-NonInteractive","-File"]
    log=(WORK/"phase10-server.log").open("wb")
    server=subprocess.Popen(ps+["run-server.ps1","-BindAddress","127.0.0.1","-Port",str(tcp),"-UdpPort",str(udp)],cwd=stage,stdout=log,stderr=subprocess.STDOUT)
    evidence["serverLauncherPid"] = server.pid
    evidence["ports"] = {"tcp": tcp, "udp": udp}
    try:
        for attempt in range(100):
            try:
                with socket.create_connection(("127.0.0.1",tcp),timeout=.2): break
            except OSError: time.sleep(.2)
        run("phase10-discovery",ps+["run-discovery.ps1","-TargetHost","127.0.0.1","-UdpPort",str(udp),"-ConnectIndex","1"])
        payload=stage/"runtime/input.bin"; payload.parent.mkdir(exist_ok=True)
        with payload.open("wb") as out:
            for i in range(1600): out.write(hashlib.sha256(str(i).encode()).digest()*2048)
        rc,upload=run("phase10-upload",ps+["run-upload.ps1","-LocalFile","runtime/input.bin","-Port",str(tcp)])
        match=re.search(r"fileId=([0-9a-f-]{36})",upload)
        if match:
            run("phase10-download",ps+["run-download.ps1","-FileId",match.group(1),"-Destination","runtime/download.bin","-Port",str(tcp)])
            evidence["roundTripSha256Verified"]=hashlib.file_digest(payload.open("rb"),"sha256").digest()==hashlib.file_digest((stage/"runtime/download.bin").open("rb"),"sha256").digest()
        run("phase10-resume",ps+["run-resume-demo.ps1","-LocalFile","runtime/input.bin","-Destination","runtime/resumed.bin","-Port",str(tcp)])
    finally:
        # Persist results before cleanup; some managed Windows sessions disallow taskkill /T.
        evidence["instructionSha256"] = hashlib.sha256((ROOT/"INSTRUCTION.md").read_bytes()).hexdigest()
        (OUT/"phase10-validation.json").write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
        ids = subprocess.run(["powershell.exe", "-NoProfile", "-Command",
                              "Get-Process java -ErrorAction SilentlyContinue | ForEach-Object { $_.Id }"],
                             capture_output=True, text=True).stdout.split()
        for candidate in ids:
            if not candidate.isdigit(): continue
            props = subprocess.run(["jcmd", candidate, "VM.system_properties"],capture_output=True,
                                   text=True,errors="replace",timeout=10).stdout
            if "sun.java.command=vn.edu.lanfileshare.server.ServerMain" in props and stage.name in props:
                subprocess.run(["powershell.exe","-NoProfile","-Command","Stop-Process -Id "+candidate+" -Force"],
                               stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try: server.wait(timeout=10)
        except subprocess.TimeoutExpired: evidence["cleanup"]="BLOCKED; close only the recorded server process"
        else: evidence["cleanup"]="Dedicated server process stopped"
        log.close()
(OUT/"phase10-validation.json").write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(evidence["tests"]), flush=True)
raise SystemExit(code)

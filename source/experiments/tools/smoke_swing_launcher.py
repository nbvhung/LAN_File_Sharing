"""Launch and close only the Swing process created for this smoke check."""
import base64,json,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
def ps(command):
    return subprocess.run(["powershell.exe","-NoProfile","-Command",command],capture_output=True,text=True,errors="replace").stdout.strip()
before=set(ps("Get-Process java -ErrorAction SilentlyContinue | ForEach-Object { $_.Id }").split())
marker="runtime/phase10-gui-launcher"
token=base64.b64encode(marker.encode()).decode().rstrip("=")
log=(ROOT/"runtime/phase10-swing.log").open("wb")
launcher=subprocess.Popen(["powershell.exe","-NoProfile","-File","run-swing.ps1","-CheckpointDirectory",marker],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
receipt={"command":"powershell -File run-swing.ps1 -CheckpointDirectory "+marker,"displayVerified":False}
owned=None
try:
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        for value in set(ps("Get-Process java -ErrorAction SilentlyContinue | ForEach-Object { $_.Id }").split())-before:
            if not value.isdigit(): continue
            props=subprocess.run(["jcmd",value,"VM.system_properties"],capture_output=True,text=True,errors="replace",timeout=5).stdout
            if "sun.java.command=vn.edu.lanfileshare.client.ui.ClientSwingMain" in props and token in props:
                owned=value
                title=ps("(Get-Process -Id "+owned+").MainWindowTitle")
                if title=="LAN File Sharing":
                    receipt["displayVerified"]=True
                    receipt["windowTitle"]=title
                    break
        if receipt["displayVerified"]: break
        time.sleep(.5)
    if owned:
        receipt["closeRequested"]=ps("(Get-Process -Id "+owned+").CloseMainWindow()")=="True"
        try: receipt["exitCode"]=launcher.wait(timeout=15)
        except subprocess.TimeoutExpired:
            ps("Stop-Process -Id "+owned+" -Force")
            receipt["cleanup"]="Forced only verified GUI after close timeout"
    else: receipt["cleanup"]="No matching JVM found; inspect launcher log"
finally:
    log.close()
    (ROOT/"statics/results/phase10-swing-launcher.json").write_text(json.dumps(receipt,indent=2),encoding="utf-8")
print(json.dumps(receipt))
raise SystemExit(0 if receipt.get("displayVerified") and receipt.get("exitCode")==0 else 1)

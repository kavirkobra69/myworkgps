import os, re, stat, subprocess, sys, tempfile, json
import yaml

wf = yaml.safe_load(open(sys.argv[1]))
script = wf["jobs"]["send"]["steps"][0]["run"]
fails = 0

def run(env, response, expect_ok=True):
    with tempfile.TemporaryDirectory() as d:
        log = os.path.join(d, "curl.log")
        fake = os.path.join(d, "curl")
        open(fake, "w").write('#!/bin/bash\nprintf "%s\\n" "$@" >> ' + log + "\ncat <<'JSON'\n" + response + "\nJSON\n")
        os.chmod(fake, 0o755)
        e = {"PATH": d + ":" + os.environ["PATH"], "BOT_TOKEN": "123:SECRETTOKEN", "CHAT_ID": "555", **env}
        p = subprocess.run(["bash", "-c", script], env=e, capture_output=True, text=True)
        args = open(log).read() if os.path.exists(log) else ""
        return p, args

def check(name, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + name)
    if not cond: fails += 1

OK = '{"ok":true}'
base = {"IN_KIND": "arrival", "IN_LANG": "en", "IN_NAME": "MmdKavir", "IN_EMP": "85", "IN_PLACE": "Mmd",
        "IN_DATE": "2026-11-09", "IN_TIME": "01:50:43", "IN_LAT": "31.9000284", "IN_LNG": "54.35826"}
p, a = run(base, OK)
text = [l for l in a.split("\n")]
sent = a.split("text=")[1].split("\n--data-urlencode\ndisable_web_page_preview")[0] if "text=" in a else ""
expected = ("WORK ATTENDANCE\n\nName: MmdKavir\nEmployee ID: 85\nWorkplace: Mmd\nDate: 2026-11-09\n"
            "Arrival Time: 01:50:43\nLocation: https://www.openstreetmap.org/?mlat=31.9000284&mlon=54.35826#map=18/31.9000284/54.35826\n\nStatus: ARRIVED")
check("english arrival text is exact", p.returncode == 0 and sent == expected)
check("uses chat id and token URL", "chat_id=555" in a and "bot123:SECRETTOKEN/sendMessage" in a)
check("token not printed to log", "SECRETTOKEN" not in p.stdout + p.stderr)

p, a = run({**base, "IN_LANG": "fa", "IN_DATE": "1405-08-18"}, OK)
sent = a.split("text=")[1].split("\n--data-urlencode\ndisable_web_page_preview")[0]
check("persian labels translated", all(w in sent for w in ["حضور در محل کار", "نام: MmdKavir", "شناسه کارمندی: 85", "محل کار: Mmd", "تاریخ: 1405-08-18", "ساعت ورود: 01:50:43", "موقعیت:", "وضعیت: وارد شد"]))
check("persian keeps numbers and link", "mlat=31.9000284" in sent and "85" in sent and "WORK ATTENDANCE" not in sent)

p, a = run({**base, "IN_EMP": ""}, OK)
check("employee id omitted when blank", "Employee ID" not in a and p.returncode == 0)

p, a = run({**base, "IN_NAME": "x\nINJECT $(touch /tmp/pwned) `id`"}, OK)
sent = a.split("text=")[1]
check("multiline/shell input stays text", p.returncode == 0 and "\nINJECT" not in sent and not os.path.exists("/tmp/pwned"))

p, a = run({**base, "IN_LAT": "1;rm -rf /"}, OK)
check("invalid coordinates rejected", p.returncode != 0 and a == "")

p, a = run({**base, "BOT_TOKEN": ""}, OK)
check("missing secrets give clear error", p.returncode != 0 and "secrets" in p.stdout.lower() and a == "")

p, a = run(base, '{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}')
check("telegram error fails the run with reason", p.returncode != 0 and "chat not found" in p.stdout)

p, a = run(base, "")
check("empty response fails the run", p.returncode != 0)

p, a = run({**base, "IN_KIND": "test"}, OK)
check("test message en", p.returncode == 0 and "Smart Attendance test message" in a)
p, a = run({**base, "IN_KIND": "test", "IN_LANG": "fa"}, OK)
check("test message fa", p.returncode == 0 and "پیام آزمایشی" in a)

p, a = run({"IN_KIND": "exit", "IN_LANG": "en", "IN_NAME": "MmdKavir", "IN_DATE": "2026-11-14", "IN_TIME": "17:05:09"}, OK)
sent = a.split("text=")[1].split("\n--data-urlencode\ndisable_web_page_preview")[0] if "text=" in a else ""
check("exit message en is exact", p.returncode == 0 and sent == "WORK EXIT\n\nName: MmdKavir\nDate: 2026-11-14\nExit Time: 17:05:09\n\nStatus: LEFT WORK")
p, a = run({"IN_KIND": "exit", "IN_LANG": "fa", "IN_NAME": "MmdKavir", "IN_DATE": "1405-08-23", "IN_TIME": "17:05:09"}, OK)
sent = a.split("text=")[1].split("\n--data-urlencode\ndisable_web_page_preview")[0] if "text=" in a else ""
check("exit message fa", p.returncode == 0 and all(w in sent for w in ["خروج از محل کار", "نام: MmdKavir", "تاریخ: 1405-08-23", "ساعت خروج: 17:05:09", "وضعیت: خارج شد"]))
p, a = run({**base, "IN_NAME": "Ali, Sara"}, OK)
check("arrival lists several people", p.returncode == 0 and "Name: Ali, Sara" in a)

inputs = wf[True]["workflow_dispatch"]["inputs"] if True in wf else wf["on"]["workflow_dispatch"]["inputs"]
check("at most 10 inputs", len(inputs) <= 10)
sys.exit(1 if fails else 0)

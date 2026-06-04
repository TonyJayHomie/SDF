"""Headless launch + click every button. Subprocess Popen is stubbed so nothing destructive runs."""
import sys, os, traceback
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))
import subprocess
class _FP:
    def __init__(self): self.stdout = iter(["fake openocd\n"]); self.returncode = 0
    def wait(self, timeout=None): return 0
    def poll(self): return 0
    def terminate(self): pass
    def kill(self): pass
subprocess.Popen = lambda cmd,*a,**kw: (_FP(), print("BLOCKED Popen:", cmd[:3] if isinstance(cmd,list) else cmd))[0]
import tkinter as tk
from tkinter import messagebox, simpledialog, filedialog
messagebox.askyesno = lambda *a,**kw: False
messagebox.showerror = lambda *a,**kw: print("ERR:", a[1] if len(a)>1 else a)
messagebox.showinfo = lambda *a,**kw: print("INFO:", a[1] if len(a)>1 else a)
simpledialog.askstring = lambda *a,**kw: ""
filedialog.askopenfilename = lambda *a,**kw: ""
filedialog.asksaveasfilename = lambda *a,**kw: ""
import hbdumper as h
errors=[]
def click(w,p):
    try:
        if w.cget("command"): w.invoke(); print(f"OK {p}: {w.cget('text')!r}")
    except Exception as e:
        msg=f"FAIL {p} ({w.cget('text')!r}): {e}"; errors.append(msg); print(msg); traceback.print_exc()
def walk(w,p="root"):
    cls=w.winfo_class()
    if cls in ("Button","TButton"): click(w,p)
    elif cls=="TNotebook":
        for tid in w.tabs():
            try: w.select(tid); w.update(); print(f"--- TAB: {w.tab(tid,'text')} ---")
            except Exception as e: errors.append(str(e))
    for ch in w.winfo_children(): walk(ch,p+"/"+ch.winfo_class())
app=h.App(); app.update(); walk(app); app.update()
import time; time.sleep(0.3); app.update(); app.destroy()
print("="*60)
print("FAILED" if errors else "ALL OK")
sys.exit(1 if errors else 0)

import sys, re
from loguru import logger; logger.remove()
from androguard.core.dex import DEX
CLASS = sys.argv[1]; PAT = sys.argv[2] if len(sys.argv)>2 else "."
rx = re.compile(PAT)
for f in ["unpacked/classes.dex","unpacked/classes2.dex"]:
    d=DEX(open(f,"rb").read())
    for c in d.get_classes():
        if c.get_name()!=CLASS: continue
        for m in c.get_methods():
            if not rx.search(m.get_name()): continue
            code=m.get_code()
            print("\n### %s->%s%s"%(c.get_name(), m.get_name(), m.get_descriptor()))
            if not code: print("  [abstract]"); continue
            for i in code.get_bc().get_instructions():
                print("   %-22s %s"%(i.get_name(), i.get_output()))

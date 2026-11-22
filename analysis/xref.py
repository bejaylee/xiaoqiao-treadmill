import sys
from loguru import logger; logger.remove()
from androguard.core.dex import DEX
NEEDLE = sys.argv[1]      # substring to match in any instruction output
ONLY_APP = "--all" not in sys.argv
hits=0
for f in ["unpacked/classes.dex","unpacked/classes2.dex"]:
    d=DEX(open(f,"rb").read())
    for c in d.get_classes():
        cn=c.get_name()
        if ONLY_APP and not cn.startswith("Lcom/qiaosports/xqiao"): continue
        for m in c.get_methods():
            code=m.get_code()
            if not code: continue
            ins=list(code.get_bc().get_instructions())
            outs=[(i.get_name(), i.get_output()) for i in ins]
            if not any(NEEDLE in o[1] for o in outs): continue
            hits+=1
            print("\n### %s->%s%s"%(cn, m.get_name(), m.get_descriptor()))
            for n,o in outs: print("   %-22s %s"%(n,o))
print("\nTOTAL METHODS:", hits)

import sys
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

TARGETS = sys.argv[1].split(",")
for f in ["unpacked/classes.dex","unpacked/classes2.dex"]:
    d = DEX(open(f,"rb").read())
    for c in d.get_classes():
        if c.get_name() not in TARGETS: continue
        print("="*76); print("CLASS", c.get_name())
        for fld in c.get_fields():
            try: v = fld.get_value()
            except Exception: v = None
            print("  FIELD %s %s = %r" % (fld.get_name(), fld.get_descriptor(), v))
        for m in c.get_methods():
            code = m.get_code()
            print("-"*66)
            print("METHOD %s%s" % (m.get_name(), m.get_descriptor()))
            if code is None:
                print("   [abstract/native]"); continue
            addr = 0
            for ins in code.get_bc().get_instructions():
                print("   %04x  %-24s %s" % (addr, ins.get_name(), ins.get_output()))
                addr += ins.get_length()*2 if ins.get_length()<10 else ins.get_length()

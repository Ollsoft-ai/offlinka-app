import os, re, sys, shutil
src, dst = sys.argv[1], sys.argv[2]
shutil.rmtree(dst, ignore_errors=True)
for root, _, files in os.walk(src):
    for f in files:
        if not f.endswith('.swift'): continue
        s = open(os.path.join(root, f)).read()
        # remove top-level #Preview("…") { … } blocks (they end with a line "}" at column 0)
        s = re.sub(r'\n#Preview\([^\n]*\{\n(?:.*\n)*?\}\n', '\n', s)
        out = os.path.join(dst, os.path.relpath(os.path.join(root, f), src))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        open(out, 'w').write(s)

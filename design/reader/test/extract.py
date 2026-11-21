"""Extract a ZIM article + everything it references into a folder, for rendering the reader CSS. Test helper only."""
import sys, re, os, posixpath, urllib.parse
from libzim.reader import Archive

def main(zim, out, *paths):
    z = Archive(zim)
    done = set()
    def save(path):
        if path in done: return None
        done.add(path)
        if not z.has_entry_by_path(path): return None
        e = z.get_entry_by_path(path)
        it = e.get_item()
        data = bytes(it.content)
        dst = os.path.join(out, path + ('.html' if it.mimetype.startswith('text/html') and not path.endswith('.html') else ''))
        os.makedirs(os.path.dirname(dst) or '.', exist_ok=True)
        open(dst, 'wb').write(data)
        base = posixpath.dirname(path)
        if it.mimetype.startswith('text/html') or it.mimetype == 'text/css':
            txt = data.decode('utf-8', 'replace')
            refs = []
            if it.mimetype.startswith('text/html'):
                refs += re.findall(r'<(?:link|script|img|source)[^>]*?(?:href|src)="([^"]+)"', txt)
                for ss in re.findall(r'srcset="([^"]+)"', txt):
                    refs += [p.strip().split(' ')[0] for p in ss.split(',')]
            refs += re.findall(r'url\(["\']?([^"\')]+)["\']?\)', txt)
            for r in refs:
                if re.match(r'^(https?:|data:|#|//)', r): continue
                r = urllib.parse.unquote(r.split('#')[0].split('?')[0])
                p = posixpath.normpath(posixpath.join(base, r))
                save(p)
        return it.mimetype
    for p in paths:
        print(p, save(p))
    print(len(done), 'entries')

main(*sys.argv[1:])

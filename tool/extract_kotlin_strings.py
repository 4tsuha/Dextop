#!/usr/bin/env python3
import concurrent.futures, hashlib, json, re, time, urllib.parse, urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
FILES=list((ROOT/'android/app/src/main/kotlin/app/freedextop/free_dextop').glob('*.kt'))
JP=re.compile(r'[ぁ-んァ-ヶ一-龠]')
LITERAL=re.compile(r'"((?:\\.|[^"\\])*)"')

def translate(value, language):
    query=urllib.parse.urlencode({'client':'gtx','sl':'ja','tl':language,'dt':'t','q':value})
    for attempt in range(6):
        try:
            with urllib.request.urlopen('https://translate.googleapis.com/translate_a/single?'+query,timeout=30) as r:
                p=json.loads(r.read())
            return ''.join(x[0] for x in p[0] if x[0])
        except Exception:
            if attempt==5: raise
            time.sleep(.5*(attempt+1))

def key_for(value, english, used):
    words=re.findall(r'[A-Za-z0-9]+',english)
    base='native'+''.join(w.capitalize() for w in words[:7])
    key=base[0].lower()+base[1:]
    if key in used: key+=hashlib.sha1(value.encode()).hexdigest()[:7]
    used.add(key); return key

def q(value): return json.dumps(value,ensure_ascii=False)

def main():
    sources={p:p.read_text() for p in FILES}
    values=sorted({m.group(1) for s in sources.values() for m in LITERAL.finditer(s)
                   if JP.search(m.group(1)) and '$' not in m.group(1)})
    used=set(); translations={lang:{} for lang in ('ja','en','zh','ko','ru')}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        english=list(pool.map(lambda v:translate(v,'en'),values))
    keys={v:key_for(v,e,used) for v,e in zip(values,english)}
    for v,e in zip(values,english): translations['ja'][keys[v]]=v; translations['en'][keys[v]]=e
    targets={'zh':'zh-CN','ko':'ko','ru':'ru'}
    jobs=[(lang,v) for lang in targets for v in values]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        outputs=list(pool.map(lambda x:translate(x[1],targets[x[0]]),jobs))
    for (lang,v),out in zip(jobs,outputs): translations[lang][keys[v]]=out
    for p,s in sources.items():
        p.write_text(LITERAL.sub(lambda m:f'NativeStrings.text({q(keys[m.group(1)])})'
            if m.group(1) in keys else m.group(0),s))
    lines=['package moe.n4tsu.dextop','','import java.util.Locale','',
           'internal object NativeStrings {','    fun text(key: String): String {',
           '        val language = Locale.getDefault().language',
           '        return (values[language] ?: values.getValue("en"))[key] ?: values.getValue("en")[key] ?: key','    }','',
           '    private val values = mapOf(']
    for lang,data in translations.items():
        lines.append(f'        {q(lang)} to mapOf(')
        for key,value in data.items(): lines.append(f'            {q(key)} to {q(value)},')
        lines.append('        ),')
    lines+=['    )','}','']
    (FILES[0].parent/'NativeStrings.kt').write_text('\n'.join(lines))

if __name__=='__main__': main()

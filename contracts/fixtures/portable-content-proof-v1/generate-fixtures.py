import hashlib,json,zipfile,io
from pathlib import Path
root=Path('contracts/fixtures/portable-content-proof-v1'); old=Path('contracts/fixtures/library-exchange-v1/assets')
sha=lambda b:hashlib.sha256(b).hexdigest()
def framed(values):
 return b''.join(str(len(s.encode())).encode()+b':'+s.encode() for s in values)
pdf=(old/'646afc0abe8b35d35cdf6c4ed88a4fcafe5a956225be324c5e082915ae7d4ccd').read_bytes()
png=(old/'e878950f8091ec010cf5cc723bdea027a8539cf7147cfea199c2f666232dcd4e').read_bytes()
b=io.BytesIO()
with zipfile.ZipFile(b,'w') as z:
 for path,content in [('mimetype',b'application/epub+zip'),('META-INF/container.xml',b'<container/>'),('chapter.xhtml','<p>안녕 😀:|\n</p>'.encode())]:
  info=zipfile.ZipInfo(path,date_time=(2026,1,1,0,0,0));z.writestr(info,content)
epub=b.getvalue()
for name,data in [('source.pdf',pdf),('image.png',png),('source.epub',epub)]: (root/name).write_bytes(data)
paragraphs=[{'paragraphId':'p:😀','text':'안녕 😀:|\n'},{'paragraphId':'empty','text':''}]
imagepath='assets/'+sha(png); pdfpath='assets/'+sha(pdf)
cases=[('text','TEXT',paragraphs,[],[],None),('pdf-empty','PDF',[],[{'path':pdfpath,'role':'pdf'}],[('source.pdf','application/pdf')],pdf),('epub-repeated-image','EPUB',paragraphs,[{'path':imagepath,'role':'image','paragraphId':'p:😀'},{'path':imagepath,'role':'image','paragraphId':'empty','alt':''}],[('image.png','image/png')],epub)]
vectors=[];tsv=[]
for name,representation,paras,refs,payloads,source in cases:
 ph=sha(framed(['pageturner.document-paragraphs.v1',str(len(paras)),*[v for p in paras for v in [p['paragraphId'],p['text']]]]))
 assets=[]
 for r in refs:
  file,mime=next((f,m) for f,m in payloads if 'assets/'+sha((root/f).read_bytes())==r['path'])
  data=(root/file).read_bytes()
  assets.append({**r,'mimeType':mime,'byteLength':len(data),'sha256':sha(data)})
 values=['pageturner.content-proof.v1','1',representation,'ko',ph,'1' if source is not None else '0']
 if source is not None: values += [str(len(source)),sha(source)]
 values += [str(len(refs))]
 for a in assets:
  values += [a['path'],a['role'],'1' if 'paragraphId' in a else '0']
  if 'paragraphId' in a: values += [a['paragraphId']]
  values += ['1' if 'alt' in a else '0']
  if 'alt' in a: values += [a['alt']]
  values += [a['mimeType'],str(a['byteLength']),a['sha256']]
 for asset in assets:
  asset.setdefault('paragraphId',None);asset.setdefault('alt',None)
 proof={'originalFileByteLength':None,'originalFileSha256':None,'version':1,'representation':representation,'language':'ko','paragraphHash':ph,'assets':assets,'sha256':sha(framed(values))}
 if source is not None: proof.update(originalFileByteLength=len(source),originalFileSha256=sha(source))
 document={'id':'ignored-copy-id','bookTitle':'ignored title','chapterTitle':'ignored chapter','language':'ko','kind':'local','paragraphs':paras,'outline':[],'notes':[],'organization':{'folder':'','tags':[],'favorite':False},'glossary':[],'assets':refs}
 vectors.append({'name':name,'representation':representation,'document':document,'payloads':[{'file':f,'mimeType':m,'path':'assets/'+sha((root/f).read_bytes())} for f,m in payloads],'originalFile':None if source is None else ('source.pdf' if representation=='PDF' else 'source.epub'),'expected':proof})
 tsv.append('\t'.join([name,proof['sha256'],ph,(proof.get('originalFileSha256') or '-'),str(proof.get('originalFileByteLength') or '-')]))
(root/'vectors.json').write_text(json.dumps(vectors,ensure_ascii=False,indent=2)+'\n')
(root/'vectors.tsv').write_text('\n'.join(tsv)+'\n')
print('\n'.join(tsv))

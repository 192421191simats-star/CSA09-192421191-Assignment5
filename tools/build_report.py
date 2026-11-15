from docx import Document
from docx.shared import Pt, RGBColor, Cm
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
import sys, re
from docx.shared import Emu
d=Document(sys.argv[1])
for s in d.sections:
    s.page_width=Cm(21.0); s.page_height=Cm(29.7)
    s.left_margin=s.right_margin=Cm(1.8); s.top_margin=s.bottom_margin=Cm(1.8)
st=d.styles
for name in ['Normal','Body Text','First Paragraph','Compact']:
    try:
        f=st[name].font; f.name='Calibri'; f.size=Pt(10.5)
    except KeyError: pass
for name,size in [('Title',22),('Subtitle',13),('Heading 1',16),('Heading 2',13),('Heading 3',11.5)]:
    try:
        f=st[name].font; f.name='Calibri'; f.size=Pt(size); f.color.rgb=RGBColor(0x1F,0x3A,0x68)
    except KeyError: pass
try: st['Source Code'].font.size=Pt(8.5)
except KeyError: pass
for t in d.tables:
    tblPr=t._tbl.tblPr; b=OxmlElement('w:tblBorders')
    for e in ['top','left','bottom','right','insideH','insideV']:
        x=OxmlElement('w:'+e); x.set(qn('w:val'),'single'); x.set(qn('w:sz'),'4'); x.set(qn('w:color'),'A0A8B8'); b.append(x)
    tblPr.append(b)
    for ri,row in enumerate(t.rows):
        for c in row.cells:
            for p in c.paragraphs:
                for r in p.runs: r.font.size=Pt(9)
            if ri==0:
                tcPr=c._tc.get_or_add_tcPr(); sh=OxmlElement('w:shd')
                sh.set(qn('w:val'),'clear'); sh.set(qn('w:color'),'auto'); sh.set(qn('w:fill'),'DCE4F2'); tcPr.append(sh)
                for p in c.paragraphs:
                    for r in p.runs: r.bold=True
# image widths: take the {width=NN%} values from the markdown, in order
md = open(sys.argv[3]).read() if len(sys.argv) > 3 else ''
fracs = [int(x) / 100 for x in re.findall(r'!\[[^\]]*\]\([^)]*\)\{width=(\d+)%\}', md)]
text_w = Cm(21.0 - 3.6)
for shp, fr in zip(d.inline_shapes, fracs):
    ratio = shp.height / shp.width
    shp.width = Emu(int(text_w * fr)); shp.height = Emu(int(text_w * fr * ratio))
d.save(sys.argv[2])

import sys, traceback
from pathlib import Path
from tkinter import messagebox
try:
    if len(sys.argv)>2 and sys.argv[1]=='--capabilities-to':
        import json
        from paperforge_desktop import capabilities
        Path(sys.argv[2]).write_text(json.dumps(capabilities(),ensure_ascii=False,indent=2),encoding='utf-8')
    elif len(sys.argv)>3 and sys.argv[1]=='--convert':
        from paperforge_desktop import process_task
        result,report=process_task(Path(sys.argv[2]),Path(sys.argv[3]),lambda _:None)
        (Path(sys.argv[3])/'compiled-result.json').write_text(__import__('json').dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    elif len(sys.argv)>1:
        from paperforge_desktop import process_task
        result,_=process_task(Path(sys.argv[1]),Path.home()/'Documents/PaperForge-results',lambda _:None)
        messagebox.showinfo('PaperForge · 转换完成',str(result))
    else:
        from gui import run
        run()
except Exception as error:
    path=Path.home()/'PaperForge-desktop-error.log'
    path.write_text(traceback.format_exc(),encoding='utf-8')
    messagebox.showerror('PaperForge',str(error)+'\n\n详细日志：'+str(path))

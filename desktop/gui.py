"""Small local GUI around the same conversion engine used by the CLI."""
import os, queue, threading, tkinter as tk
from tkinter import ttk,filedialog,messagebox
from pathlib import Path
from paperforge_desktop import process_task,capabilities,create_task

def run():
    root=tk.Tk();root.title('PaperForge · 离线电脑助手');root.geometry('820x600');root.minsize(680,480)
    root.configure(bg='#f6f8f4')
    style=ttk.Style();style.theme_use('clam')
    style.configure('.',font=('Microsoft YaHei UI',10),background='#f6f8f4',foreground='#243a2f')
    style.configure('TButton',padding=(14,10));style.configure('Accent.TButton',background='#176b54',foreground='white')
    style.map('Accent.TButton',background=[('active','#235f4d')])
    style.configure('Title.TLabel',font=('Microsoft YaHei UI',22,'bold'))
    frame=ttk.Frame(root,padding=28);frame.pack(fill='both',expand=True)
    ttk.Label(frame,text='让文档保持本来的样子',style='Title.TLabel').pack(anchor='w')
    ttk.Label(frame,text='Office 排版转换 · PDF 版面重建 · 复杂表格提取\n本机处理，无需登录。手机任务包与电脑文件均可使用。').pack(anchor='w',pady=(10,20))
    output=tk.StringVar(value=str(Path.home()/'Documents/PaperForge-results'))
    row=ttk.Frame(frame);row.pack(fill='x')
    ttk.Label(row,text='结果目录').pack(side='left',padx=(0,12))
    ttk.Entry(row,textvariable=output).pack(side='left',fill='x',expand=True)
    ttk.Button(row,text='选择',command=lambda: output.set(filedialog.askdirectory() or output.get())).pack(side='left',padx=(8,0))
    tasks=queue.Queue();events=queue.Queue();pending=[];working=False
    log=tk.Text(frame,height=14,wrap='word',font=('Microsoft YaHei UI',10),bg='white',fg='#243a2f',relief='flat',padx=14,pady=12,state='disabled')
    def add_log(text):
        log.configure(state='normal');log.insert('end',text+'\n');log.see('end');log.configure(state='disabled')
    def worker():
        while True:
            job=tasks.get()
            if job is None:return
            path,destination=job
            if path is None:return
            try:
                result,report=process_task(path,destination,lambda text: events.put(('log',text)))
                events.put(('log','已完成：'+str(result)))
            except Exception as error:events.put(('log','转换未完成：'+str(error)))
            finally:tasks.task_done()
    threading.Thread(target=worker,daemon=True).start()
    def choose():
        for file in filedialog.askopenfilenames(title='选择手机导出的任务包',filetypes=[('PaperForge 任务包','*.zip')]):
            add_log('排队：'+Path(file).name);tasks.put((file,Path(output.get())))
    def open_output():
        directory=Path(output.get());directory.mkdir(parents=True,exist_ok=True)
        if os.name=='nt':os.startfile(directory)
        else:messagebox.showinfo('结果目录',str(directory))
    buttons=ttk.Frame(frame);buttons.pack(fill='x',pady=16)
    ttk.Button(buttons,text='添加手机任务包',style='Accent.TButton',command=choose).pack(side='left')
    ttk.Button(buttons,text='打开结果目录',command=open_output).pack(side='left',padx=10)
    direct=ttk.Frame(frame);direct.pack(fill='x',pady=(0,12))
    mode=tk.StringVar(value='Office → PDF')
    join=tk.BooleanVar(value=False)
    strategy=tk.StringVar(value='自动')
    modes={'Office → PDF':'office-pdf','Office → DOCX':'office-docx','Office → ODT':'office-odt',
        'Office → RTF':'office-rtf','Office → XLSX':'office-xlsx','Office → ODS':'office-ods',
        'Office → CSV':'office-csv','Office → PPTX':'office-pptx','Office → ODP':'office-odp',
        'PDF → Word 版面':'pdf-docx','PDF / 图片 → XLSX 表格':'tables-xlsx',
        'PDF / 图片 → CSV 表格包':'tables-csv','PDF → PPTX 整页外观':'pdf-pptx'}
    ttk.Combobox(direct,textvariable=mode,values=list(modes),state='readonly',width=31).pack(side='left')
    def computer_file():
        source=filedialog.askopenfilename(title='选择电脑上的文档')
        if source:
            try:tasks.put((create_task(source,modes[mode.get()],Path(output.get()), {'ocr':True,'joinTables':join.get(),'tableStrategy':{'自动':'auto','有线表':'lines_strict','无线表':'text'}[strategy.get()]}),Path(output.get())));add_log('排队：'+Path(source).name)
            except Exception as error:messagebox.showerror('添加失败',str(error))
    ttk.Button(direct,text='选择电脑文件转换',command=computer_file).pack(side='left',padx=10)
    def engines():
        def inspect():
            try:
                status=capabilities()
                events.put(('log','Office 引擎：'+str(status['libreoffice'] or '未找到，请设置 config.json')))
                for package,label in [('pdf2docx','Word 版面重建'),('openpyxl','Excel 表格输出'),('rapidocr-onnxruntime','扫描表格 OCR')]:
                    version=status['packages'].get(package)
                    events.put(('log',label+'：'+('可用 · '+version if version else '未检测到版本，请检查安装')) )
            except Exception as error:events.put(('log',str(error)))
        threading.Thread(target=inspect,daemon=True).start()
    ttk.Button(buttons,text='检查引擎',command=engines).pack(side='right')
    table_options=ttk.Frame(frame);table_options.pack(fill='x',pady=(0,10))
    ttk.Label(table_options,text='电脑文件表格设置').pack(side='left',padx=(0,10))
    ttk.Combobox(table_options,textvariable=strategy,values=['自动','有线表','无线表'],state='readonly',width=9).pack(side='left')
    ttk.Checkbutton(table_options,text='合并相邻页同表头表格',variable=join).pack(side='left',padx=12)
    log.pack(fill='both',expand=True)
    ttk.Label(frame,text='完成后将 PaperForge-result-*.zip 传回手机，在「转换中心 → 电脑助手结果导入」打开。',wraplength=740).pack(anchor='w',pady=(14,0))
    def poll():
        try:
            while True:
                _,message=events.get_nowait();add_log(message)
        except queue.Empty:pass
        root.after(150,poll)
    def close():
        if tasks.unfinished_tasks and not messagebox.askyesno('仍有任务','关闭将中断尚未完成的转换，是否关闭？'):return
        root.destroy()
    root.protocol('WM_DELETE_WINDOW',close);poll();engines();root.mainloop()

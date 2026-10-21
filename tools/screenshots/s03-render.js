waitSmart(); sleep(2000);
edt(function () { reset(); useHost("production", "prd-web-01.acme.internal"); open("roles/nginx/templates/nginx.conf.j2"); hideTool("Project"); editor().getCaretModel().moveToOffset(0); editor().getScrollingModel().scrollVertically(0); });
sleep(1500);
edt(function () { action("TextEditorWithPreview.Layout.EditorAndPreview"); });
sleep(800);
edt(function () { action("TextEditorWithPreview.SplitVertically"); });
sleep(4000);
edt(function () { clean(); shot(OUT + "/03-template-preview.png"); });

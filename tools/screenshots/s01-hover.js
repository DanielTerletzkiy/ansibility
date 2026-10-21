waitSmart(); sleep(2000);
edt(function () { reset(); useHost("production", "prd-web-01.acme.internal"); open("roles/nginx/templates/nginx.conf.j2"); selectInProject("roles/nginx/templates/nginx.conf.j2"); caretAt("nginx_worker_connections"); editor().getScrollingModel().scrollVertically(0); });
sleep(3000);
edt(function () { editor().getCaretModel().moveToOffset(editor().getCaretModel().getOffset() + 4); action("QuickJavaDoc"); });
sleep(3000);
edt(function () { clean(); shot(OUT + "/01-hover-effective-value.png"); });

waitSmart(); sleep(1000);
// module documentation
edt(function () { reset(); hideTool("Project"); open("roles/nginx/tasks/main.yml"); caretAt("ansible.builtin.template"); editor().getCaretModel().moveToOffset(editor().getCaretModel().getOffset() + 18); });
sleep(2500);
edt(function () { action("QuickJavaDoc"); });
sleep(3500);
edt(function () { clean(); shot(OUT + "/g03-module-docs.png"); closePopups(); });
// find usages of a role variable
edt(function () { reset(); hideTool("Project"); open("roles/nginx/defaults/main.yml"); caretAt("nginx_listen_port"); });
sleep(2500);
edt(function () { action("FindUsages"); });
sleep(5000);
edt(function () { clean(); shot(OUT + "/g04-find-usages.png"); });
// context switcher popup from the status bar
edt(function () { reset(); hideTool("Project"); open("roles/nginx/templates/nginx.conf.j2"); });
sleep(2500);
later(function () { action("Ansibility.SwitchContext"); });
sleep(3000);
edt(function () { clean(); shot(OUT + "/g05-switch-context.png"); closePopups(); });

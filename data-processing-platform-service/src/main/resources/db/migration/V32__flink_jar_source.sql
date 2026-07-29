-- Persists the "在线编写" source alongside the compiled jar, so a jar built
-- this way can be viewed/edited/recompiled later instead of the source being
-- thrown away right after compileAndPackage() runs (previously the request
-- body's sourceCode never got past the controller method). NULL for jars
-- created via plain file upload - there's no source to store for those, and
-- the JAR 包管理 UI only offers a "查看/编辑代码" action when source_code is
-- present.
ALTER TABLE flink_jar
  ADD COLUMN class_name VARCHAR(300) NULL COMMENT '入口类全限定名，仅在线编写创建的 jar 有值',
  ADD COLUMN source_code MEDIUMTEXT NULL COMMENT '编译用的 Java 源码，仅在线编写创建的 jar 有值',
  ADD COLUMN target_type VARCHAR(32) NULL COMMENT '编译时选择的目标数据源类型，同 JavaJobBuildService 的 targetType';

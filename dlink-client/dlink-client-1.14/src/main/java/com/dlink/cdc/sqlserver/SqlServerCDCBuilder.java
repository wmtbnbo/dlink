package com.dlink.cdc.sqlserver;

import com.dlink.assertion.Asserts;
import com.dlink.cdc.AbstractCDCBuilder;
import com.dlink.cdc.CDCBuilder;
import com.dlink.constant.ClientConstant;
import com.dlink.constant.FlinkParamConstant;
import com.dlink.model.FlinkCDCConfig;

import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ververica.cdc.connectors.sqlserver.SqlServerSource;
import com.ververica.cdc.connectors.sqlserver.table.StartupOptions;

/**
 * sql server CDC
 *
 * @author 郑文豪
 */
public class SqlServerCDCBuilder extends AbstractCDCBuilder implements CDCBuilder {
    protected static final Logger logger = LoggerFactory.getLogger(SqlServerCDCBuilder.class);

    private static final String KEY_WORD = "sqlserver-cdc";
    private static final String METADATA_TYPE = "SqlServer";

    public SqlServerCDCBuilder() {
    }

    public SqlServerCDCBuilder(FlinkCDCConfig config) {
        super(config);
    }

    @Override
    public String getHandle() {
        return KEY_WORD;
    }

    @Override
    public CDCBuilder create(FlinkCDCConfig config) {
        return new SqlServerCDCBuilder(config);
    }

    @Override
    public DataStreamSource<String> build(StreamExecutionEnvironment env) {
        String database = config.getDatabase();
        Properties debeziumProperties = new Properties();
        // 为部分转换添加默认值
        debeziumProperties.setProperty("bigint.unsigned.handling.mode", "long");
        debeziumProperties.setProperty("decimal.handling.mode", "string");
        for (Map.Entry<String, String> entry : config.getDebezium().entrySet()) {
            if (Asserts.isNotNullString(entry.getKey()) && Asserts.isNotNullString(entry.getValue())) {
                debeziumProperties.setProperty(entry.getKey(), entry.getValue());
            }
        }
        // 添加jdbc参数注入
        Properties jdbcProperties = new Properties();
        for (Map.Entry<String, String> entry : config.getJdbc().entrySet()) {
            if (Asserts.isNotNullString(entry.getKey()) && Asserts.isNotNullString(entry.getValue())) {
                jdbcProperties.setProperty(entry.getKey(), entry.getValue());
            }
        }
        final SqlServerSource.Builder<String> sourceBuilder = SqlServerSource.<String>builder()
                .hostname(config.getHostname())
                .port(config.getPort())
                .username(config.getUsername())
                .password(config.getPassword());
        if (Asserts.isNotNullString(database)) {
            String[] databases = database.split(FlinkParamConstant.SPLIT);
            sourceBuilder.database(databases[0]);
        } else {
            sourceBuilder.database(new String());
        }
        List<String> schemaTableNameList = config.getSchemaTableNameList();
        if (Asserts.isNotNullCollection(schemaTableNameList)) {
            sourceBuilder.tableList(schemaTableNameList.toArray(new String[schemaTableNameList.size()]));
        } else {
            sourceBuilder.tableList(new String[0]);
        }
        //sourceBuilder.deserializer(new JsonDebeziumDeserializationSchema());
        sourceBuilder.deserializer(new SqlServerJsonDebeziumDeserializationSchema());
        if (Asserts.isNotNullString(config.getStartupMode())) {
            switch (config.getStartupMode().toLowerCase()) {
                case "initial":
                    sourceBuilder.startupOptions(StartupOptions.initial());
                    break;
                case "latest-offset":
                    sourceBuilder.startupOptions(StartupOptions.latest());
                    break;
                default:
            }
        } else {
            sourceBuilder.startupOptions(StartupOptions.latest());
        }
        sourceBuilder.debeziumProperties(debeziumProperties);
        final DataStreamSource<String> sqlServer_cdc_source = env.addSource(sourceBuilder.build(), "SqlServer CDC Source");
        return sqlServer_cdc_source;
    }

    @Override
    public List<String> getSchemaList() {
        List<String> schemaList = new ArrayList<>();
        String schema = config.getDatabase();
        if (Asserts.isNotNullString(schema)) {
            String[] schemas = schema.split(FlinkParamConstant.SPLIT);
            Collections.addAll(schemaList, schemas);
        }
        List<String> tableList = getTableList();
        for (String tableName : tableList) {
            tableName = tableName.trim();
            if (Asserts.isNotNullString(tableName) && tableName.contains(".")) {
                String[] names = tableName.split("\\\\.");
                if (!schemaList.contains(names[0])) {
                    schemaList.add(names[0]);
                }
            }
        }
        return schemaList;
    }

    @Override
    public Map<String, Map<String, String>> parseMetaDataConfigs() {
        Map<String, Map<String, String>> allConfigMap = new HashMap<>();
        List<String> schemaList = getSchemaList();
        for (String schema : schemaList) {
            Map<String, String> configMap = new HashMap<>();
            configMap.put(ClientConstant.METADATA_TYPE, METADATA_TYPE);
            StringBuilder sb = new StringBuilder("jdbc:sqlserver://");
            sb.append(config.getHostname());
            sb.append(":");
            sb.append(config.getPort());
            sb.append(";database=");
            sb.append(config.getDatabase());
            configMap.put(ClientConstant.METADATA_NAME, sb.toString());
            configMap.put(ClientConstant.METADATA_URL, sb.toString());
            configMap.put(ClientConstant.METADATA_USERNAME, config.getUsername());
            configMap.put(ClientConstant.METADATA_PASSWORD, config.getPassword());
            allConfigMap.put(schema, configMap);
        }
        return allConfigMap;
    }

    @Override
    public String getSchemaFieldName() {
        return "schema";
    }
}

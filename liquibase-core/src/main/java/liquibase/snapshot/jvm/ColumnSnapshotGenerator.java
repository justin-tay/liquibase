package liquibase.snapshot.jvm;

import liquibase.Scope;
import liquibase.database.AbstractJdbcDatabase;
import liquibase.database.Database;
import liquibase.database.OfflineConnection;
import liquibase.database.core.*;
import liquibase.exception.DatabaseException;
import liquibase.executor.ExecutorService;
import liquibase.logging.Logger;
import liquibase.snapshot.CachedRow;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.JdbcDatabaseSnapshot;
import liquibase.statement.DatabaseFunction;
import liquibase.statement.core.RawSqlStatement;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.*;
import liquibase.util.BooleanUtil;
import liquibase.util.SqlUtil;
import liquibase.util.StringUtil;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColumnSnapshotGenerator extends JdbcSnapshotGenerator {

    /**
     * This attribute indicates whether we need to process a column object. It is visible only
     * in scope of snapshot process.
     */
    private static final String LIQUIBASE_COMPLETE = "liquibase-complete";
    protected static final String COLUMN_DEF_COL = "COLUMN_DEF";

    private static final String POSTGRES_STRING_VALUE_REGEX = "'(.*)'::[\\w .]+";
    private static final Pattern POSTGRES_STRING_VALUE_PATTERN = Pattern.compile(POSTGRES_STRING_VALUE_REGEX);
    private static final String POSTGRES_NUMBER_VALUE_REGEX = "\\(?(\\d*)\\)?::[\\w .]+";
    private static final Pattern POSTGRES_NUMBER_VALUE_PATTERN = Pattern.compile(POSTGRES_NUMBER_VALUE_REGEX);

    private final ColumnAutoIncrementService columnAutoIncrementService = new ColumnAutoIncrementService();


    public ColumnSnapshotGenerator() {
        super(Column.class, new Class[]{Table.class, View.class});
    }

    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot) throws DatabaseException {
        if (BooleanUtil.isTrue(((Column) example).getComputed()) || BooleanUtil.isTrue(((Column) example).getDescending())) {
            return example;
        }
        Database database = snapshot.getDatabase();

        Relation relation = ((Column) example).getRelation();

        Schema schema = relation.getSchema();
        try {
            Column column = null;

            if (example.getAttribute(LIQUIBASE_COMPLETE, false)) {
                column = (Column) example;
                example.setAttribute(LIQUIBASE_COMPLETE, null);

                return column;
            }

            String catalogName = ((AbstractJdbcDatabase) database).getJdbcCatalogName(schema);
            String schemaName = ((AbstractJdbcDatabase) database).getJdbcSchemaName(schema);
            String tableName = relation.getName();
            String columnName = example.getName();

            JdbcDatabaseSnapshot.CachingDatabaseMetaData databaseMetaData =
                    ((JdbcDatabaseSnapshot) snapshot).getMetaDataFromCache();

            List<CachedRow> metaDataColumns = databaseMetaData.getColumns(catalogName, schemaName, tableName, columnName);
            List<CachedRow> metaDataNotNullConst = databaseMetaData.getNotNullConst(catalogName, schemaName, tableName);

            if (!metaDataColumns.isEmpty()) {
                CachedRow data = metaDataColumns.get(0);
                column = readColumn(data, relation, database);
                setAutoIncrementDetails(column, database, snapshot);

                populateValidateNullableIfNeeded(column, metaDataNotNullConst, database);
            }

            example.setAttribute(LIQUIBASE_COMPLETE, null);

            if (column == null && database instanceof PostgresDatabase && looksLikeFunction(example.getName())) {
                ((Column) example).setComputed(true);
                return example;
            }

            return column;
        } catch (DatabaseException | SQLException e) {
            throw new DatabaseException(e);
        }
    }

    private void populateValidateNullableIfNeeded(Column column, List<CachedRow> metaDataNotNullConst, Database database) {
        if (!(database instanceof OracleDatabase)) {
            return;
        }
        String name = column.getName();
        for (CachedRow cachedRow : metaDataNotNullConst) {
            Object columnNameObj = cachedRow.get("COLUMN_NAME");
            if (columnNameObj == null) {
                throw new AssertionError("Please check query to fetch data for notNullConst!. "
                        + "I didn't fetch needed data");
            }
            if (name.equalsIgnoreCase(columnNameObj.toString())) {
                final String VALIDATE = "VALIDATED";
                Object validated = cachedRow.get(VALIDATE);
                if (validated == null) {
                    break;
                }
                // Oracle returns NULLABLE=Y for columns that have not null constraints that are not validated
                // we have to check the search_condition to verify if it is really nullable
                String searchCondition = cachedRow.getString("SEARCH_CONDITION");
                searchCondition = searchCondition == null ? "" : searchCondition.toUpperCase();
                String nullable = cachedRow.getString("NULLABLE");
                String constraintName = cachedRow.getString("CONSTRAINT_NAME");
                if ("NOT VALIDATED".equalsIgnoreCase(validated.toString())
                        && "Y".equalsIgnoreCase(nullable)
                        && searchCondition.matches("\"?\\w+\" IS NOT NULL")) {
                    // not validated not null constraint found
                    column.setNullable(false);
                    column.setValidateNullable(false);
                }
                if (Boolean.FALSE.equals(column.isNullable()) && hasValidObjectName(constraintName)) {
                    column.setAttribute("notNullConstraintName", constraintName);
                }
            }
        }
    }

    private static boolean hasValidObjectName(String objectName) {
        if (StringUtil.isEmpty(objectName)) {
            return false;
        }
        return !objectName.startsWith("SYS_") && !objectName.startsWith("BIN$");
    }

    @Override
    protected void addTo(DatabaseObject foundObject, DatabaseSnapshot snapshot) throws DatabaseException {
        if (!snapshot.getSnapshotControl().shouldInclude(Column.class)) {
            return;
        }
        if (foundObject instanceof Relation) {
            Database database = snapshot.getDatabase();
            Relation relation = (Relation) foundObject;
            List<CachedRow> allColumnsMetadataRs;
            try {

                JdbcDatabaseSnapshot.CachingDatabaseMetaData databaseMetaData =
                        ((JdbcDatabaseSnapshot) snapshot).getMetaDataFromCache();

                Schema schema;

                schema = relation.getSchema();
                allColumnsMetadataRs = databaseMetaData.getColumns(
                        ((AbstractJdbcDatabase) database).getJdbcCatalogName(schema),
                        ((AbstractJdbcDatabase) database).getJdbcSchemaName(schema),
                        relation.getName(),
                        null);
                List<CachedRow> metaDataNotNullConst = databaseMetaData.getNotNullConst(schema.getCatalogName(), schema.getName(), relation.getName());

                /*
                 * Microsoft SQL Server, SAP SQL Anywhere and probably other RDBMS guarantee non-duplicate
                 * ORDINAL_POSITIONs for the columns of a single table. But they do not guarantee there are no gaps
                 * in that integers (e.g. if columns have been deleted). So we need to check for that and renumber
                 * if needed.
                 */
                TreeMap<Integer, CachedRow> treeSet = new TreeMap<>();
                for (CachedRow row : allColumnsMetadataRs) {
                    treeSet.put(row.getInt("ORDINAL_POSITION"), row);
                }
                Logger log = Scope.getCurrentScope().getLog(getClass());

                // Now we can iterate through the sorted list and repair if needed.
                int currentOrdinal = 0;
                for (CachedRow row : treeSet.values()) {
                    currentOrdinal++;
                    int rsOrdinal = row.getInt("ORDINAL_POSITION");
                    if (rsOrdinal != currentOrdinal) {
                        log.fine(
                                String.format(
                                        "Repairing ORDINAL_POSITION with gaps for table=%s, column name=%s, " +
                                                "bad ordinal=%d, new ordinal=%d",
                                        relation.getName(),
                                        row.getString("COLUMN_NAME"),
                                        rsOrdinal,
                                        currentOrdinal
                                )
                        );
                        row.set("ORDINAL_POSITION", currentOrdinal);
                    }
                }

                // Iterate through all (repaired) rows and add the columns to our result.
                for (CachedRow row : allColumnsMetadataRs) {
                    Column column = readColumn(row, relation, database);
                    setAutoIncrementDetails(column, database, snapshot);
                    populateValidateNullableIfNeeded(column, metaDataNotNullConst, database);
                    column.setAttribute(LIQUIBASE_COMPLETE, true);
                    relation.getColumns().add(column);
                }
            } catch (SQLException e) {
                throw new DatabaseException(e);
            }
        }

    }

    protected void setAutoIncrementDetails(Column column, Database database, DatabaseSnapshot snapshot) {
        if ((column.getAutoIncrementInformation() != null) && (database.getConnection() != null) &&
                !(database.getConnection() instanceof OfflineConnection) &&
                (column.getRelation() != null) && (column.getSchema() != null)) {

            Column.AutoIncrementInformation autoIncrementInformation =
                    this.columnAutoIncrementService.obtainSequencesInformation(database, snapshot)
                            .get(String.format("%s.%s.%s", column.getSchema().getName(), column.getRelation().getName(), column.getName()));
            if (autoIncrementInformation != null) {
                column.setAutoIncrementInformation(autoIncrementInformation);
            }
        }
    }

    protected Column readColumn(CachedRow columnMetadataResultSet, Relation table, Database database)
            throws SQLException, DatabaseException {
        String rawTableName = (String) columnMetadataResultSet.get("TABLE_NAME");
        String rawColumnName = (String) columnMetadataResultSet.get("COLUMN_NAME");
        String rawSchemaName = StringUtil.trimToNull((String) columnMetadataResultSet.get("TABLE_SCHEM"));
        String rawCatalogName = StringUtil.trimToNull((String) columnMetadataResultSet.get("TABLE_CAT"));
        String remarks = StringUtil.trimToNull((String) columnMetadataResultSet.get("REMARKS"));
        if (remarks != null) {
            // Comes back escaped sometimes
            remarks = remarks.replace("''", "'");
        }
        Integer position = columnMetadataResultSet.getInt("ORDINAL_POSITION");

        Column column = new Column();
        column.setName(StringUtil.trimToNull(rawColumnName));
        column.setRelation(table);
        column.setRemarks(remarks);
        column.setOrder(position);
        Boolean isComputed = columnMetadataResultSet.getBoolean("IS_COMPUTED");
        if (isComputed != null) {
            column.setComputed(isComputed);
        }


        if (columnMetadataResultSet.get("IS_FILESTREAM") != null && (Boolean) columnMetadataResultSet.get("IS_FILESTREAM")) {
            column.setAttribute("fileStream", true);
        }
        if (columnMetadataResultSet.get("IS_ROWGUIDCOL") != null && (Boolean) columnMetadataResultSet.get("IS_ROWGUIDCOL")) {
            column.setAttribute("rowGuid", true);
        }
        if (database instanceof OracleDatabase) {
            String nullable = columnMetadataResultSet.getString("NULLABLE");
            if ("Y".equals(nullable)) {
                column.setNullable(true);
            } else {
                column.setNullable(false);
            }
        } else {
            Integer nullable = columnMetadataResultSet.getInt("NULLABLE");
            if (nullable != null) {
                if (nullable == DatabaseMetaData.columnNoNulls) {
                    column.setNullable(false);
                } else if (nullable == DatabaseMetaData.columnNullable) {
                    column.setNullable(true);
                } else if (nullable == DatabaseMetaData.columnNullableUnknown) {
                    Scope.getCurrentScope().getLog(getClass()).info("Unknown nullable state for column "
                            + column + ". Assuming nullable");
                    column.setNullable(true);
                }
            }
        }

        if (database.supportsAutoIncrement() && table instanceof Table) {
            column = this.columnAutoIncrementService.enableColumnAutoIncrementIfAvailable(column, database,
                    columnMetadataResultSet, rawCatalogName, rawSchemaName, rawTableName, rawColumnName);
        }

        DataType type = readDataType(columnMetadataResultSet, column, database);
        column.setType(type);

        Object defaultValue = readDefaultValue(columnMetadataResultSet, column, database);

        // TODO Is uppercasing the potential function name always a good idea?
        // In theory, we could get a quoted function name (inprobable, but not impossible)
        if ((defaultValue instanceof DatabaseFunction) && ((DatabaseFunction) defaultValue)
                .getValue().matches("\\w+")) {
            defaultValue = new DatabaseFunction(((DatabaseFunction) defaultValue).getValue().toUpperCase());
        }
        column.setDefaultValue(defaultValue);
        column.setDefaultValueConstraintName(columnMetadataResultSet.getString("COLUMN_DEF_NAME"));

        return column;
    }

    /**
     * Processes metadata of a column, e.g. name, type and default value. We start with the result of the JDBC
     * {@link DatabaseMetaData}.getColumns() method. Depending on Database, additional columns might be present.
     *
     * @param columnMetadataResultSet the result from the JDBC getColumns() call for the column
     * @param column                  logical definition of the column (object form)
     * @param database                the database from which the column originates
     * @return a DataType object with detailed information about the type
     * @throws DatabaseException If an error occurs during processing (mostly caused by Exceptions in JDBC calls)
     */
    protected DataType readDataType(CachedRow columnMetadataResultSet, Column column, Database database) throws DatabaseException {

        if (database instanceof OracleDatabase) {
            String dataType = columnMetadataResultSet.getString("DATA_TYPE_NAME");
            dataType = dataType.replace("VARCHAR2", "VARCHAR");
            dataType = dataType.replace("NVARCHAR2", "NVARCHAR");

            DataType type = new DataType(dataType);
            type.setDataTypeId(columnMetadataResultSet.getInt("DATA_TYPE"));
            if (dataType.equalsIgnoreCase("NUMBER")) {
                type.setColumnSize(columnMetadataResultSet.getInt("DATA_PRECISION"));
//                if (type.getColumnSize() == null) {
//                    type.setColumnSize(38);
//                }
                type.setDecimalDigits(columnMetadataResultSet.getInt("DATA_SCALE"));
//                if (type.getDecimalDigits() == null) {
//                    type.setDecimalDigits(0);
//                }
//            type.setRadix(10);
            } else {
                if ("FLOAT".equalsIgnoreCase(dataType)) { //FLOAT [(precision)]
                    type.setColumnSize(columnMetadataResultSet.getInt("DATA_PRECISION"));
                } else {
                    type.setColumnSize(columnMetadataResultSet.getInt("DATA_LENGTH"));
                }

                boolean isTimeStampDataType = dataType.toUpperCase().contains("TIMESTAMP");

                if (isTimeStampDataType || dataType.equalsIgnoreCase("NCLOB") || dataType.equalsIgnoreCase("BLOB") || dataType.equalsIgnoreCase("CLOB")) {
                    type.setColumnSize(null);
                } else if (dataType.equalsIgnoreCase("NVARCHAR") || dataType.equalsIgnoreCase("NCHAR")) {
                    type.setColumnSize(columnMetadataResultSet.getInt("CHAR_LENGTH"));
                    type.setColumnSizeUnit(DataType.ColumnSizeUnit.CHAR);
                } else {
                    String charUsed = columnMetadataResultSet.getString("CHAR_USED");
                    DataType.ColumnSizeUnit unit = null;
                    if ("C".equals(charUsed)) {
                        unit = DataType.ColumnSizeUnit.CHAR;
                        type.setColumnSize(columnMetadataResultSet.getInt("CHAR_LENGTH"));
                    } else if ("B".equals(charUsed)) {
                        unit = DataType.ColumnSizeUnit.BYTE;
                    }
                    type.setColumnSizeUnit(unit);
                }
            }


            return type;
        }

        String columnTypeName = (String) columnMetadataResultSet.get("TYPE_NAME");

        if (database instanceof MSSQLDatabase) {
            if ("numeric() identity".equalsIgnoreCase(columnTypeName)) {
                columnTypeName = "numeric";
            } else if ("decimal() identity".equalsIgnoreCase(columnTypeName)) {
                columnTypeName = "decimal";
            } else if ("xml".equalsIgnoreCase(columnTypeName)) {
                columnMetadataResultSet.set("COLUMN_SIZE", null);
                columnMetadataResultSet.set("DECIMAL_DIGITS", null);
            } else if ("datetimeoffset".equalsIgnoreCase(columnTypeName)
                    || "time".equalsIgnoreCase(columnTypeName)) {
                columnMetadataResultSet.set("COLUMN_SIZE", columnMetadataResultSet.getInt("DECIMAL_DIGITS"));
                columnMetadataResultSet.set("DECIMAL_DIGITS", null);
            }
        } else if (database instanceof PostgresDatabase) {
            columnTypeName = database.unescapeDataTypeName(columnTypeName);
            // https://www.postgresql.org/message-id/20061016193942.GF23302%40svana.org says that internally array datatypes are defined with an underscore prefix.
            if (columnTypeName.startsWith("_")) {
                columnTypeName = columnTypeName.replaceFirst("_", "").concat("[]");
            }
        }

        if (database instanceof FirebirdDatabase) {
            if ("BLOB SUB_TYPE 0".equals(columnTypeName)) {
                columnTypeName = "BLOB";
            }
            if ("BLOB SUB_TYPE 1".equals(columnTypeName)) {
                columnTypeName = "CLOB";
            }
        }

        if ((database instanceof MySQLDatabase) && ("ENUM".equalsIgnoreCase(columnTypeName) || "SET".equalsIgnoreCase
                (columnTypeName))) {
            try {
                String boilerLength;
                if ("ENUM".equalsIgnoreCase(columnTypeName)) {
                    boilerLength = "7";
                } else {
                    // SET
                    boilerLength = "6";
                }

                List<String> enumValues = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).queryForList(
                        new RawSqlStatement(
                                "SELECT DISTINCT SUBSTRING_INDEX(SUBSTRING_INDEX(SUBSTRING(COLUMN_TYPE, " + boilerLength +
                                        ", LENGTH(COLUMN_TYPE) - " + boilerLength +
                                        " - 1 ), \"','\", 1 + units.i + tens.i * 10) , \"','\", -1)\n" +
                                        "FROM INFORMATION_SCHEMA.COLUMNS\n" +
                                        "CROSS JOIN (SELECT 0 AS i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 " +
                                        "UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) units\n" +
                                        "CROSS JOIN (SELECT 0 AS i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 " +
                                        "UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) tens\n" +
                                        "WHERE TABLE_SCHEMA = '" + column.getSchema().getName() + "' \n" +
                                        "AND TABLE_NAME = '" + column.getRelation().getName() + "' \n" +
                                        "AND COLUMN_NAME = '" + column.getName() + "'\n" +
                                        "ORDER BY tens.i, units.i"), String.class);
                String enumClause = "";
                for (String enumValue : enumValues) {
                    enumClause += "'" + enumValue + "', ";
                }
                enumClause = enumClause.replaceFirst(", $", "");
                return new DataType(columnTypeName + "(" + enumClause + ")");
            } catch (DatabaseException e) {
                Scope.getCurrentScope().getLog(getClass()).warning("Error fetching enum values", e);
            }
        }

        DataType.ColumnSizeUnit columnSizeUnit = DataType.ColumnSizeUnit.BYTE;

        int dataType = columnMetadataResultSet.getInt("DATA_TYPE");
        Integer columnSize = null;
        Integer decimalDigits = null;

        if (!database.dataTypeIsNotModifiable(columnTypeName)) {
            // don't set size for types like int4, int8 etc
            columnSize = columnMetadataResultSet.getInt("COLUMN_SIZE");
            decimalDigits = columnMetadataResultSet.getInt("DECIMAL_DIGITS");
            if ((decimalDigits != null) && decimalDigits.equals(0)) {
                if (dataType == Types.TIME && database instanceof PostgresDatabase) {
                    //that is allowed
                } else {
                    decimalDigits = null;
                }
            }
        }

        Integer radix = columnMetadataResultSet.getInt("NUM_PREC_RADIX");

        Integer characterOctetLength = columnMetadataResultSet.getInt("CHAR_OCTET_LENGTH");

        if (database instanceof AbstractDb2Database) {
            String typeName = columnMetadataResultSet.getString("TYPE_NAME");
            if (("DBCLOB".equalsIgnoreCase(typeName) || "GRAPHIC".equalsIgnoreCase(typeName)
                    || "VARGRAPHIC".equalsIgnoreCase(typeName)) && (columnSize != null)) {
                //Stored as double length chars
                columnSize = columnSize / 2;
            }
            if ("TIMESTAMP".equalsIgnoreCase(columnTypeName) && (decimalDigits == null)) {
                // Actually a date
                columnTypeName = "DATE";
                dataType = Types.DATE;
            }
        }

        if ((database instanceof PostgresDatabase) && columnSize != null) {
            if (columnSize.equals(Integer.MAX_VALUE)) {
                columnSize = null;
            } else if (columnTypeName.equalsIgnoreCase("numeric") && columnSize.equals(0)) {
                columnSize = null;
            }

        }

        // For SAP (Sybase) SQL ANywhere, JDBC returns "LONG(2147483647) binary" (the number is 2^31-1)
        // but when creating a column, LONG BINARY must not have parameters.
        // The same applies to LONG(...) VARCHAR.
        if (database instanceof SybaseASADatabase
                && ("LONG BINARY".equalsIgnoreCase(columnTypeName) || "LONG VARCHAR".equalsIgnoreCase(columnTypeName))) {
            columnSize = null;
        }

        DataType type = new DataType(columnTypeName);
        type.setDataTypeId(dataType);

        /*
         * According to the description of DatabaseMetaData.getColumns, the content of the "COLUMN_SIZE" column is
         * pretty worthless for datetime/timestamp columns:
         *
         * "For datetime datatypes, this is the length in characters of the String representation
         * (assuming the maximum allowed precision of the fractional seconds component)."
         * In the case of TIMESTAMP columns, the information we are really looking for
         * (the fractional digits) is located in the column DECIMAL_DIGITS.
         */
        int jdbcType = columnMetadataResultSet.getInt("DATA_TYPE");

        // Java 8 compatibility notes: When upgrading this project to JDK8 and beyond, also execute this if-branch
        // if jdbcType is TIMESTAMP_WITH_TIMEZONE (does not exist yet in JDK7)
        if (jdbcType == Types.TIMESTAMP) {

            if (decimalDigits == null) {
                type.setColumnSize(null);
            } else {
                type.setColumnSize((decimalDigits != database.getDefaultFractionalDigitsForTimestamp()) ?
                        decimalDigits : null
                );
            }

            type.setDecimalDigits(null);
        } else {
            type.setColumnSize(columnSize);
            type.setDecimalDigits(decimalDigits);
        }
        type.setRadix(radix);
        type.setCharacterOctetLength(characterOctetLength);
        type.setColumnSizeUnit(columnSizeUnit);


        return type;
    }

    protected Object readDefaultValue(CachedRow columnMetadataResultSet, Column columnInfo, Database database) {
        if (database instanceof MSSQLDatabase) {
            Object defaultValue = columnMetadataResultSet.get(COLUMN_DEF_COL);

            if (("(NULL)".equals(defaultValue))) {
                columnMetadataResultSet.set(COLUMN_DEF_COL, new DatabaseFunction("null"));
            }
        }

        if ((database instanceof OracleDatabase) && (columnMetadataResultSet.get(COLUMN_DEF_COL) == null)) {
            columnMetadataResultSet.set(COLUMN_DEF_COL, columnMetadataResultSet.get("DATA_DEFAULT"));

            if ((columnMetadataResultSet.get(COLUMN_DEF_COL) != null) && "NULL".equalsIgnoreCase((String)
                    columnMetadataResultSet.get(COLUMN_DEF_COL))) {
                columnMetadataResultSet.set(COLUMN_DEF_COL, null);
            }

            Object columnDef = columnMetadataResultSet.get(COLUMN_DEF_COL);
            if ("CHAR".equalsIgnoreCase(columnInfo.getType().getTypeName()) && (columnDef instanceof String) && !
                    ((String) columnDef).startsWith("'") && !((String) columnDef).endsWith("'")) {
                return new DatabaseFunction((String) columnDef);
            }

            if ("YES".equals(columnMetadataResultSet.get("VIRTUAL_COLUMN"))) {
                Object virtColumnDef = columnMetadataResultSet.get(COLUMN_DEF_COL);
                if ((virtColumnDef != null) && !"null".equals(virtColumnDef)) {
                    columnMetadataResultSet.set(COLUMN_DEF_COL, "GENERATED ALWAYS AS (" + virtColumnDef + ")");
                }
            }

            Object defaultValue = columnMetadataResultSet.get(COLUMN_DEF_COL);
            if ((defaultValue instanceof String)) {
                String lowerCaseDefaultValue = ((String) defaultValue).toLowerCase();
                if (lowerCaseDefaultValue.contains("iseq$$") && lowerCaseDefaultValue.endsWith(".nextval")) {
                    columnMetadataResultSet.set(COLUMN_DEF_COL, null);
                }

            }
        }

        if (database instanceof PostgresDatabase) {
            if (columnInfo.isAutoIncrement()) {
                columnMetadataResultSet.set(COLUMN_DEF_COL, null);
            }
            Object defaultValue = columnMetadataResultSet.get(COLUMN_DEF_COL);
            if ((defaultValue instanceof String)) {
                Matcher matcher = POSTGRES_STRING_VALUE_PATTERN.matcher((String) defaultValue);
                if (matcher.matches()) {
                    defaultValue = matcher.group(1);
                } else {
                    matcher = POSTGRES_NUMBER_VALUE_PATTERN.matcher((String) defaultValue);
                    if (matcher.matches()) {
                        defaultValue = matcher.group(1);
                    }

                }
                columnMetadataResultSet.set(COLUMN_DEF_COL, defaultValue);
            }
        }

        if (
                (database instanceof AbstractDb2Database) &&
                        ((columnMetadataResultSet.get(COLUMN_DEF_COL) != null) &&
                                "NULL".equalsIgnoreCase((String) columnMetadataResultSet.get(COLUMN_DEF_COL)))) {
            columnMetadataResultSet.set(COLUMN_DEF_COL, null);
        }

        return SqlUtil.parseValue(database, columnMetadataResultSet.get(COLUMN_DEF_COL), columnInfo.getType());
    }

    /**
     * {@link IndexSnapshotGenerator} fails to differentiate computed and non-computed column's for {@link PostgresDatabase}
     * assume that if COLUMN_NAME contains parentesised expression -- its function reference.
     * should handle cases like:
     * - ((name)::text)
     * - lower/upper((name)::text)
     * - (name)::text || '- concatenation example'
     */
    private boolean looksLikeFunction(String columnName) {
        return columnName.contains("(");
    }
}
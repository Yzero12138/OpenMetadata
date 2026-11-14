package org.openmetadata.service.integration;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.SimpleNode;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;

public final class IntegrationSql {
  public static final int MAX_QUERY_LENGTH = 16384;
  private static final Set<String> PARAMETERS = Set.of("biz_date", "window_start", "window_end");
  private static final Set<String> UNSAFE_FUNCTIONS =
      Set.of(
          "dblink",
          "dblink_exec",
          "openquery",
          "openrowset",
          "opendatasource",
          "nextval",
          "setval",
          "pg_sleep",
          "pg_read_file",
          "pg_read_binary_file",
          "pg_write_file",
          "pg_notify",
          "set_config",
          "pg_advisory_lock",
          "pg_advisory_lock_shared",
          "pg_advisory_xact_lock",
          "pg_advisory_xact_lock_shared",
          "pg_try_advisory_lock",
          "pg_try_advisory_lock_shared",
          "pg_try_advisory_xact_lock",
          "pg_try_advisory_xact_lock_shared",
          "pg_advisory_unlock",
          "pg_advisory_unlock_shared",
          "pg_advisory_unlock_all",
          "pg_terminate_backend",
          "pg_cancel_backend",
          "lo_import",
          "lo_export",
          "load_file",
          "get_lock",
          "release_lock");

  private IntegrationSql() {}

  public record TableReference(String schema, String table) {}

  public static void validate(String query, Set<String> allowedSchemas, String defaultSchema) {
    validate(query, allowedSchemas, defaultSchema, JdbcDialect.Postgres);
  }

  public static void validate(
      String query, Set<String> allowedSchemas, String defaultSchema, JdbcDialect dialect) {
    Parsed parsed = parse(query, defaultSchema, dialect);
    if (allowedSchemas == null || !allowedSchemas.contains(defaultSchema)) {
      throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
    }
    for (SourceTable table : parsed.tables()) {
      if (!allowedSchemas.contains(table.reference().schema())) {
        throw new IntegrationException("SCHEMA_NOT_ALLOWED", 400);
      }
    }
  }

  public static String render(String query, LocalDate businessDate) {
    if (businessDate == null || businessDate.getYear() < 1 || businessDate.getYear() > 9998) {
      throw invalid();
    }
    try {
      Lexed lexed = lex(query);
      StringBuilder result = new StringBuilder(query);
      for (int i = lexed.parameters().size() - 1; i >= 0; i--) {
        Parameter parameter = lexed.parameters().get(i);
        String value =
            switch (parameter.name()) {
              case "biz_date" -> businessDate.toString();
              case "window_start" -> businessDate + " 00:00:00";
              default -> businessDate.plusDays(1) + " 00:00:00";
            };
        result.replace(parameter.start(), parameter.end(), "'" + value + "'");
      }
      return result.toString();
    } catch (DateTimeException e) {
      throw invalid();
    }
  }

  public static String render(
      String query, LocalDate businessDate, LocalDate windowStart, LocalDate windowEnd) {
    if (businessDate == null
        || businessDate.getYear() < 1
        || businessDate.getYear() > 9998
        || !businessDate.equals(windowStart)
        || windowEnd == null
        || windowEnd.getYear() > 9999
        || !windowEnd.isAfter(windowStart)
        || windowEnd.isAfter(windowStart.plusDays(31))) {
      throw invalid();
    }
    Lexed lexed = lex(query);
    StringBuilder result = new StringBuilder(query);
    for (int i = lexed.parameters().size() - 1; i >= 0; i--) {
      Parameter parameter = lexed.parameters().get(i);
      String value =
          switch (parameter.name()) {
            case "biz_date" -> businessDate.toString();
            case "window_start" -> windowStart + " 00:00:00";
            default -> windowEnd + " 00:00:00";
          };
      result.replace(parameter.start(), parameter.end(), "'" + value + "'");
    }
    return result.toString();
  }

  public static Set<String> parameters(String query) {
    return lex(query).parameters().stream()
        .map(Parameter::name)
        .collect(Collectors.toUnmodifiableSet());
  }

  public static void requireInitializationParameters(String query, String batchUnit) {
    Set<String> parameters = parameters(query);
    if ("MONTH".equals(batchUnit)) {
      if (!parameters.containsAll(Set.of("window_start", "window_end"))
          || parameters.contains("biz_date")) {
        throw new IntegrationException("SQL_INITIALIZATION_PARAMETERS_REQUIRED", 400);
      }
    } else if (!"DAY".equals(batchUnit)) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    } else if (parameters.isEmpty()) {
      throw new IntegrationException("SQL_DATE_PARAMETER_REQUIRED", 400);
    }
  }

  public static List<TableReference> references(String query, String defaultSchema) {
    return List.copyOf(
        parse(query, defaultSchema).tables().stream()
            .map(SourceTable::reference)
            .collect(Collectors.toCollection(LinkedHashSet::new)));
  }

  public static List<TableReference> references(
      String query, String defaultSchema, JdbcDialect dialect) {
    return List.copyOf(
        parse(query, defaultSchema, dialect).tables().stream()
            .map(
                table -> {
                  String name = table.reference().table();
                  if (!table.quotedTable()) {
                    if (dialect == JdbcDialect.Postgres) {
                      name = name.toLowerCase(Locale.ROOT);
                    } else if (dialect == JdbcDialect.Oracle) {
                      name = name.toUpperCase(Locale.ROOT);
                    }
                  }
                  return new TableReference(table.reference().schema(), name);
                })
            .collect(Collectors.toCollection(LinkedHashSet::new)));
  }

  public static String renderForSource(
      String query, LocalDate businessDate, String defaultSchema, JdbcDialect dialect) {
    return render(qualifyForSource(query, defaultSchema, dialect), businessDate);
  }

  public static String renderForSource(
      String query,
      LocalDate businessDate,
      LocalDate windowStart,
      LocalDate windowEnd,
      String defaultSchema,
      JdbcDialect dialect) {
    return render(
        qualifyForSource(query, defaultSchema, dialect), businessDate, windowStart, windowEnd);
  }

  private static String qualifyForSource(String query, String defaultSchema, JdbcDialect dialect) {
    if (dialect == null) {
      throw invalid();
    }
    Parsed parsed = parse(query, defaultSchema, dialect);
    StringBuilder qualified = new StringBuilder(query);
    parsed.tables().stream()
        .distinct()
        .sorted(Comparator.comparingInt(SourceTable::offset).reversed())
        .forEach(
            table -> {
              if (table.unqualified()) {
                qualified.insert(table.offset(), dialect.quote(defaultSchema) + ".");
              } else {
                qualified.replace(
                    table.offset(),
                    table.offset() + table.schemaTokenLength(),
                    dialect.quote(table.reference().schema()));
              }
            });
    return qualified.toString();
  }

  private static Parsed parse(String query, String defaultSchema) {
    return parse(query, defaultSchema, JdbcDialect.Postgres);
  }

  private static Parsed parse(String query, String defaultSchema, JdbcDialect dialect) {
    if (!IntegrationValidator.safeIdentifier(defaultSchema) || dialect == null) {
      throw invalid();
    }
    Lexed lexed = lex(query);
    if (lexed.parameters().isEmpty()) {
      throw new IntegrationException("SQL_DATE_PARAMETER_REQUIRED", 400);
    }
    try {
      var statements =
          CCJSqlParserUtil.parseStatements(
              query,
              parser ->
                  parser
                      .withSquareBracketQuotation(true)
                      .withBackslashEscapeCharacter(true)
                      .withTimeOut(1000));
      if (statements.size() != 1 || !(statements.getFirst() instanceof Select select)) {
        throw invalid();
      }
      List<SourceTable> tables = new ArrayList<>();
      walkSelect(select, Set.of(), defaultSchema, dialect, query, tables, new int[] {0}, 0);
      return new Parsed(List.copyOf(tables));
    } catch (JSQLParserException | IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static void walkSelect(
      Select select,
      Set<String> inheritedCtes,
      String defaultSchema,
      JdbcDialect dialect,
      String query,
      List<SourceTable> tables,
      int[] count,
      int depth) {
    if (depth > 64
        || !(select instanceof PlainSelect
            || select instanceof ParenthesedSelect
            || select instanceof SetOperationList)) {
      throw invalid();
    }
    if (select instanceof PlainSelect plain
        && ((plain.getIntoTables() != null && !plain.getIntoTables().isEmpty())
            || plain.getIntoTempTable() != null
            || plain.getForMode() != null
            || plain.getForUpdateTable() != null
            || plain.getForClause() != null
            || plain.isEmitChanges())) {
      throw invalid();
    }
    Set<String> ctes = new HashSet<>(inheritedCtes);
    if (select.getWithItemsList() != null) {
      for (var cte : select.getWithItemsList()) {
        String rawName = cte.getAlias().getName();
        String name = unquote(rawName);
        if (!IntegrationValidator.safeIdentifier(name)) {
          throw invalid();
        }
        if (cte.isRecursive()) {
          ctes.add(canonical(rawName, dialect));
        }
        walkSelect(cte.getSelect(), ctes, defaultSchema, dialect, query, tables, count, depth + 1);
        ctes.add(canonical(rawName, dialect));
      }
    }
    if (select.getASTNode() == null) {
      throw invalid();
    }
    walkNode(
        select.getASTNode(), select, ctes, defaultSchema, dialect, query, tables, count, depth + 1);
  }

  private static void walkNode(
      Node node,
      Select owner,
      Set<String> ctes,
      String defaultSchema,
      JdbcDialect dialect,
      String query,
      List<SourceTable> tables,
      int[] count,
      int depth) {
    if (depth > 128 || ++count[0] > 20000) {
      throw invalid();
    }
    SimpleNode syntax = (SimpleNode) node;
    Object value = syntax.jjtGetValue();
    if (value instanceof Select nested && nested != owner) {
      walkSelect(nested, ctes, defaultSchema, dialect, query, tables, count, depth + 1);
      return;
    }
    if (value instanceof TableFunction
        || value instanceof NextValExpression
        || value instanceof VariableAssignment) {
      throw invalid();
    }
    if (value instanceof Column column
        && column.getTable() != null
        && column.getTable().getName() != null
        && "NEXTVAL".equalsIgnoreCase(unquote(column.getColumnName()))) {
      throw invalid();
    }
    if (value instanceof Function function) {
      String qualifiedName = function.getName();
      String name =
          unquote(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1))
              .toLowerCase(Locale.ROOT);
      String packageName =
          qualifiedName
              .replace("\"", "")
              .replace("`", "")
              .replace("[", "")
              .replace("]", "")
              .toLowerCase(Locale.ROOT);
      boolean dynamicXml =
          name.startsWith("query_to_xml")
              || name.startsWith("table_to_xml")
              || name.startsWith("schema_to_xml")
              || name.startsWith("database_to_xml")
              || name.startsWith("cursor_to_xml")
              || (Set.of("getxml", "getxmltype").contains(name)
                  && packageName.endsWith("dbms_xmlgen." + name));
      if (UNSAFE_FUNCTIONS.contains(name) || name.startsWith("dblink_") || dynamicXml) {
        throw invalid();
      }
    }
    if (value instanceof Table table) {
      String name = unquote(table.getName());
      if (table.getDatabase().getDatabaseName() != null
          || table.getNameParts().size() > 2
          || !IntegrationValidator.safeIdentifier(name)) {
        throw invalid();
      }
      boolean unqualified = table.getSchemaName() == null;
      String cteName = canonical(table.getName(), dialect);
      boolean cteReference = unqualified && ctes.contains(cteName);
      if (unqualified
          && !cteReference
          && dialect != JdbcDialect.Postgres
          && dialect != JdbcDialect.Oracle
          && ctes.stream().anyMatch(cte -> cte.equalsIgnoreCase(cteName))) {
        throw invalid();
      }
      if (!cteReference) {
        String schema = unqualified ? defaultSchema : unquote(table.getSchemaName());
        if (!IntegrationValidator.safeIdentifier(schema)) {
          throw invalid();
        }
        tables.add(
            new SourceTable(
                new TableReference(schema, name),
                unqualified,
                offset(query, syntax.jjtGetFirstToken()),
                unqualified ? 0 : syntax.jjtGetFirstToken().image.length(),
                !table.getName().equals(name)));
      }
    }
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      walkNode(
          node.jjtGetChild(i),
          owner,
          ctes,
          defaultSchema,
          dialect,
          query,
          tables,
          count,
          depth + 1);
    }
  }

  private static int offset(String query, Token token) {
    int offset = token.absoluteBegin - 1;
    if (offset < 0 || !query.startsWith(token.image, offset)) {
      throw invalid();
    }
    return offset;
  }

  private static Lexed lex(String query) {
    if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
      throw invalid();
    }
    List<Parameter> parameters = new ArrayList<>();
    for (int i = 0; i < query.length(); ) {
      char character = query.charAt(i);
      if (character == '\0'
          || (Character.isISOControl(character) && !Character.isWhitespace(character))) {
        throw invalid();
      }
      boolean boundary = i == 0 || !Character.isJavaIdentifierPart(query.charAt(i - 1));
      int alternativePrefix =
          boundary && query.regionMatches(true, i, "q'", 0, 2)
              ? 2
              : boundary && query.regionMatches(true, i, "nq'", 0, 3) ? 3 : 0;
      if (alternativePrefix != 0) {
        int delimiter = i + alternativePrefix;
        if (delimiter >= query.length() || Character.isWhitespace(query.charAt(delimiter))) {
          throw invalid();
        }
        char close =
            switch (query.charAt(delimiter)) {
              case '[' -> ']';
              case '(' -> ')';
              case '{' -> '}';
              case '<' -> '>';
              default -> query.charAt(delimiter);
            };
        int end = query.indexOf(close + "'", delimiter + 1);
        if (end < 0) {
          throw invalid();
        }
        i = end + 2;
      } else if (boundary
          && (character == 'e' || character == 'E')
          && i + 1 < query.length()
          && query.charAt(i + 1) == '\'') {
        i = quotedEnd(query, i + 1, '\'', true);
      } else if (character == '\'' || character == '"' || character == '`' || character == '[') {
        i = quotedEnd(query, i, character == '[' ? ']' : character, false);
      } else if (query.startsWith("--", i)) {
        if (i + 2 < query.length() && !Character.isWhitespace(query.charAt(i + 2))) {
          throw invalid();
        }
        int end = query.indexOf('\n', i + 2);
        i = end < 0 ? query.length() : end;
      } else if (query.startsWith("/*", i)) {
        if (query.startsWith("/*!", i) || query.regionMatches(true, i, "/*m!", 0, 4)) {
          throw invalid();
        }
        int level = 1;
        i += 2;
        while (i < query.length() && level > 0) {
          if (query.startsWith("/*", i)) {
            level++;
            i += 2;
          } else if (query.startsWith("*/", i)) {
            level--;
            i += 2;
          } else {
            i++;
          }
        }
        if (level != 0) {
          throw invalid();
        }
      } else if (character == '$' && dollarDelimiter(query, i) != null) {
        String delimiter = dollarDelimiter(query, i);
        int end = query.indexOf(delimiter, i + delimiter.length());
        if (end < 0) {
          throw invalid();
        }
        i = end + delimiter.length();
      } else if (query.startsWith("::", i)) {
        i += 2;
      } else if (character == ':'
          && i + 1 < query.length()
          && Character.isJavaIdentifierStart(query.charAt(i + 1))) {
        int end = i + 2;
        while (end < query.length() && Character.isJavaIdentifierPart(query.charAt(end))) {
          end++;
        }
        String name = query.substring(i + 1, end);
        if (!PARAMETERS.contains(name)) {
          throw new IntegrationException("SQL_PARAMETER_UNSUPPORTED", 400);
        }
        parameters.add(new Parameter(name, i, end));
        i = end;
      } else if (character == '?' || character == ':' || character == '@') {
        throw new IntegrationException(
            character == '@' ? "INVALID_SOURCE_QUERY" : "SQL_PARAMETER_UNSUPPORTED", 400);
      } else {
        i++;
      }
    }
    return new Lexed(List.copyOf(parameters));
  }

  private static int quotedEnd(String query, int start, char endQuote, boolean escaped) {
    for (int i = start + 1; i < query.length(); i++) {
      if (query.charAt(i) == '\\' && i + 1 < query.length()) {
        char next = query.charAt(i + 1);
        if (escaped || next == '\\') {
          i++;
          continue;
        }
        if (next == endQuote) {
          throw invalid();
        }
      }
      if (query.charAt(i) == endQuote) {
        if (i + 1 < query.length() && query.charAt(i + 1) == endQuote) {
          i++;
        } else {
          return i + 1;
        }
      }
    }
    throw invalid();
  }

  private static String dollarDelimiter(String query, int start) {
    int end = start + 1;
    while (end < query.length()
        && (Character.isLetterOrDigit(query.charAt(end)) || query.charAt(end) == '_')) {
      end++;
    }
    return end < query.length() && query.charAt(end) == '$'
        ? query.substring(start, end + 1)
        : null;
  }

  private static String unquote(String identifier) {
    if (identifier != null && identifier.length() >= 2) {
      char first = identifier.charAt(0);
      char last = identifier.charAt(identifier.length() - 1);
      if ((first == '"' && last == '"')
          || (first == '`' && last == '`')
          || (first == '[' && last == ']')) {
        return identifier.substring(1, identifier.length() - 1);
      }
    }
    return identifier;
  }

  private static String canonical(String identifier, JdbcDialect dialect) {
    String name = unquote(identifier);
    if (!name.equals(identifier)) {
      return name;
    }
    return switch (dialect) {
      case Postgres -> name.toLowerCase(Locale.ROOT);
      case Oracle -> name.toUpperCase(Locale.ROOT);
      default -> name;
    };
  }

  private static IntegrationException invalid() {
    return new IntegrationException("INVALID_SOURCE_QUERY", 400);
  }

  private record Parameter(String name, int start, int end) {}

  private record Lexed(List<Parameter> parameters) {}

  private record SourceTable(
      TableReference reference,
      boolean unqualified,
      int offset,
      int schemaTokenLength,
      boolean quotedTable) {}

  private record Parsed(List<SourceTable> tables) {}
}

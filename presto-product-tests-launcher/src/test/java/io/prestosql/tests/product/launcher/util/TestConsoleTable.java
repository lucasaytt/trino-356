/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.tests.product.launcher.util;

import org.testng.annotations.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class TestConsoleTable
{
    @Test
    public void testRenderTableWithSingleElement()
    {
        ConsoleTable table = new ConsoleTable();
        table.addRow("hello");
        assertThat(table.render()).isEqualTo("| hello |");
    }

    @Test
    public void testRenderTableWithMultipleElements()
    {
        ConsoleTable table = new ConsoleTable();
        table.addRow("hello", "world");
        assertThat(table.render()).isEqualTo("| hello | world |");
    }

    @Test
    public void testRenderTableWithSimpleLine()
    {
        ConsoleTable table = new ConsoleTable();
        table.addSeparator();
        table.addRow("hello", "world");
        table.addSeparator();

        assertThat(table.render()).isEqualTo(
                "+-------+-------+\n" +
                "| hello | world |\n" +
                "+-------+-------+");
    }

    @Test
    public void testRenderTableWithDifferentColumnWidths()
    {
        ConsoleTable table = new ConsoleTable();
        table.addSeparator();
        table.addRow("hello", "world");
        table.addRow("a", "b");
        table.addRow("prestosql", "rocks");
        table.addSeparator();

        assertThat(table.render()).isEqualTo(
                "+-----------+-------+\n" +
                "|     hello | world |\n" +
                "|         a |     b |\n" +
                "| prestosql | rocks |\n" +
                "+-----------+-------+");
    }

    @Test
    public void testRenderTableWithMissingColumns()
    {
        ConsoleTable table = new ConsoleTable();
        table.addSeparator();
        table.addRow("hello", "world");
        table.addRow("a", "b");
        table.addRow("prestosql");
        table.addSeparator();

        assertThat(table.render()).isEqualTo(
                "+-----------+-------+\n" +
                "|     hello | world |\n" +
                "|         a |     b |\n" +
                "| prestosql |       |\n" +
                "+-----------+-------+");
    }

    @Test
    public void testRenderTableWithDifferentRowPaddings()
    {
        ConsoleTable table = new ConsoleTable();
        table.addSeparator();
        table.addRow(ConsoleTable.Alignment.RIGHT, "hello", "world");
        table.addSeparator();
        table.addRow(ConsoleTable.Alignment.CENTER, "a", "b");
        table.addRow("prestosql", "rocks");
        table.addSeparator();

        assertThat(table.render()).isEqualTo(
                "+-----------+-------+\n" +
                "|     hello | world |\n" +
                "+-----------+-------+\n" +
                "|     a     |   b   |\n" +
                "| prestosql | rocks |\n" +
                "+-----------+-------+");
    }
}

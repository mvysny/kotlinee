package com.github.vok.framework

import com.github.vok.karibudsl.getColumnBy
import com.vaadin.data.BeanPropertySet
import com.vaadin.data.HasValue
import com.vaadin.data.PropertyDefinition
import com.vaadin.data.provider.ConfigurableFilterDataProvider
import com.vaadin.server.SerializableConsumer
import com.vaadin.shared.Registration
import com.vaadin.shared.ui.ValueChangeMode
import com.vaadin.ui.Component
import com.vaadin.ui.Grid
import com.vaadin.ui.HasValueChangeMode
import com.vaadin.ui.components.grid.HeaderRow
import java.io.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.streams.toList

/**
 * Maintains a set of fields - monitors bound fields for values and provides [filter] of type F.
 * Fires [onFilterChangeListeners] on every filter change.
 * @param T the type of items in the grid.
 * @param F the type of the filter
 * @param filterFieldFactory used to create filters from filter components by invoking [FilterFieldFactory.createFilter]
 */
class FilterBinder<T: Any, F: Any>(val filterFieldFactory: FilterFieldFactory<T, F>,
                                   val filterFactory: FilterFactory<F>) : Serializable {

    /**
     * The current filter as generated by [filterFieldFactory] from the most current states of all bound filter fields.
     * Updated on every filter field change.
     */
    var filter: F? = null
        private set

    /**
     * Invoked when the [filter] changes.
     */
    val onFilterChangeListeners = listeners<SerializableConsumer<F?>>()

    private val filterComponents = mutableMapOf<HasValue<*>, FilterFieldWatcher<*>>()

    /**
     * Binds given filtering field to a container - starts filtering based on the contents of the field, and starts watching for field value changes.
     * Does not add the component to the [headerRow].
     * @param field The field which provides the filtering values, not null. [FilterFieldFactory.createFilter] is used to convert
     * the field's value to a filter.
     * @param property The bean property on which the filtering will be performed, not null.
     */
    fun <V> bind(field: HasValue<V?>, property: PropertyDefinition<T, V?>) {
        unbind(field)
        val filterFieldWatcher= FilterFieldWatcher(field, property)
        filterFieldWatcher.registration = field.addValueChangeListener(filterFieldWatcher)
        filterComponents[field] = filterFieldWatcher
    }

    fun unbind(field: HasValue<*>) {
        filterComponents.remove(field)?.unregisterWatcher()
    }

    fun unbindAll() {
        filterComponents.keys.toList().forEach { unbind(it) }
    }

    /**
     * Listens on value change on given field and updates [ConfigurableFilterDataProvider.setFilter] accordingly.
     * @property field The field which provides the filtering values.
     * @property property The bean property on which the filtering will be performed.
     * @param V the value type
     */
    private inner class FilterFieldWatcher<V>(private val field: HasValue<V?>, private val property: PropertyDefinition<T, V?>) :
        HasValue.ValueChangeListener<V?> {

        /**
         * The current container filter, may be null if no filtering is currently needed because the
         * field's value indicates that the filtering is disabled for this column (e.g. the text filter is blank, the filter field is cleared, etc).
         */
        var currentFilter: F? = null

        init {
            valueChange()
        }

        override fun valueChange(event: HasValue.ValueChangeEvent<V?>) {
            valueChange()
        }

        private fun valueChange(value: V? = field.value) {
            val newFilter = filterFieldFactory.createFilter(value, field, property)
            setNewFilter(newFilter)
        }

        private fun setNewFilter(newFilter: F?) {
            if (newFilter != currentFilter) {
                currentFilter = newFilter
                recomputeFilter()
            }
        }

        fun unregisterWatcher() {
            setNewFilter(null)
            registration.remove()
        }

        lateinit var registration: Registration
    }

    private fun recomputeFilter() {
        val filters = filterComponents.values.mapNotNull { it.currentFilter } .toSet()
        filter = filterFactory.and(filters)
        onFilterChangeListeners.fire.accept(filter)
    }
}

/**
 * Wraps [HeaderRow] and tracks filter components; also provides support for watching of changes to the filters.
 * @param T the type of items in the grid.
 * @param F the type of the filters accepted by grid's [ConfigurableFilterDataProvider].
 * @param grid the owner grid.
 * @property headerRow the wrapped header row
 */
@Suppress("UNCHECKED_CAST")
class FilterRow<T: Any, F: Any>(val grid: Grid<T>, val itemClass: KClass<T>, val headerRow: HeaderRow,
                                val filterFieldFactory: FilterFieldFactory<T, F>,
                                val filterFactory: FilterFactory<F>) : Serializable {

    /**
     * Binds filters from this row. Whenever a bound filter changes, it is set to [Grid.getDataProvider].
     */
    val binder: FilterBinder<T, F> = FilterBinder(filterFieldFactory, filterFactory)
    init {
        binder.onFilterChangeListeners.add(SerializableConsumer { filter ->
            (grid.dataProvider as ConfigurableFilterDataProvider<T, F, F>).setFilter(filter)
        })
    }

    /**
     * Invoked when the filter changes.
     */
    val onFilterChangeListeners: Listeners<SerializableConsumer<F?>>
        get() = binder.onFilterChangeListeners

    /**
     * Re-generates all filter components in this header row. Removes all old filter components and
     * creates a new set and populates them into the [headerRow].
     */
    fun generateFilterComponents(valueChangeMode: ValueChangeMode = ValueChangeMode.LAZY) {
        clear()

        val properties: Map<String, PropertyDefinition<T, *>> =
            BeanPropertySet.get(itemClass.java).properties.toList().associateBy { it.name }
        for (propertyId in grid.columns.mapNotNull { it.id }) {
            val property = properties[propertyId]
            val field: HasValue<*>? = if (property == null) null else filterFieldFactory.createField(property)
            (field as? HasValueChangeMode)?.valueChangeMode = valueChangeMode
            val cell = headerRow.getCell(propertyId)
            if (field != null) {
                binder.bind(field as HasValue<Any?>, property!! as PropertyDefinition<T, Any?>)
                cell.component = field as Component
            }
        }
    }

    /**
     * Unbinds all filters and removes all filter components from this [headerRow].
     */
    fun clear() {
        binder.unbindAll()
        grid.columns.forEach { column ->
            // can't set cell.component to null, that would fail with NPE!
            headerRow.getCell(column).text = "" // this also removes the cell field from the header row
        }
    }

    /**
     * [clear]s and removes the [headerRow] from the grid.
     */
    fun remove() {
        clear()
        grid.removeHeaderRow(headerRow)
    }

    /**
     * Returns the filter component filtering given [property]. Fails if no filter component has been generated for
     * that property.
     */
    fun getFilterComponent(property: KProperty1<T, *>): HasValue<Any?> {
        val column = grid.getColumnBy(property)
        val component = headerRow.getCell(column).component ?: throw IllegalArgumentException("There is no filter configured for $property")
        return component as HasValue<Any?>
    }
}

/**
 * Re-creates filters in this header row. Simply call `grid.appendHeaderRow().generateFilterComponents(grid)` to automatically attach
 * filters to non-generated columns. Please note that filters are not re-generated when the container data source is changed.
 * @param T the type of items in the grid.
 * @param grid the owner grid.
 * @param filterFieldFactory used to create the filters themselves. If null, [DefaultFilterFieldFactory] is used.
 * @param valueChangeMode how eagerly to apply the filtering after the user changes the filter value. Only applied to [HasValueChangeMode];
 * typically only applies to inline filter
 * components (most importantly [com.vaadin.ui.TextField]), typically ignored for popup components (such as [com.github.vok.framework.NumberFilterPopup])
 * where the values are applied after the user clicks the "Apply" button. Defaults to [ValueChangeMode.LAZY].
 */
@Suppress("UNCHECKED_CAST")
fun <T: Any, F: Any> HeaderRow.generateFilterComponents(grid: Grid<T>, itemClass: KClass<T>,
                                                        filterFactory: FilterFactory<F>,
                                                filterFieldFactory: FilterFieldFactory<T, F> = DefaultFilterFieldFactory(filterFactory),
                                                valueChangeMode: ValueChangeMode = ValueChangeMode.LAZY): FilterRow<T, F> {
    val filterRow = FilterRow(grid, itemClass, this, filterFieldFactory, filterFactory)
    filterRow.generateFilterComponents(valueChangeMode)
    return filterRow
}

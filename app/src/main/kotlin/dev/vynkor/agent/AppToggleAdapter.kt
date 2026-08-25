package dev.vynkor.agent

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.databinding.ItemAppToggleBinding

/** One installed app row in the notification forward filter. */
data class AppToggleRow(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val muted: Boolean,
)

class AppToggleAdapter(
    private val onToggle: (AppToggleRow, Boolean) -> Unit,
) : ListAdapter<AppToggleRow, AppToggleAdapter.Holder>(DIFF) {

    fun submit(rows: List<AppToggleRow>) {
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppToggleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemAppToggleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: AppToggleRow) {
            binding.appIcon.setImageDrawable(row.icon)
            binding.appLabel.text = row.label
            // Detach before setting checked state — recycled rows would
            // otherwise fire the listener with a stale row's position.
            binding.appSwitch.setOnCheckedChangeListener(null)
            binding.appSwitch.isChecked = row.muted
            binding.appSwitch.setOnCheckedChangeListener { _, checked ->
                onToggle(row, checked)
            }
            itemView.setOnClickListener { binding.appSwitch.toggle() }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppToggleRow>() {
            override fun areItemsTheSame(oldItem: AppToggleRow, newItem: AppToggleRow) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppToggleRow, newItem: AppToggleRow) =
                oldItem == newItem
        }
    }
}

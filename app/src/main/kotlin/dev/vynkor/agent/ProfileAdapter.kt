package dev.vynkor.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.databinding.ItemProfileBinding

/**
 * R-16/R-31: DiffUtil-driven host profiles list with ViewBinding. The active
 * badge is part of the payload so switching profiles rebinds only two rows.
 */
class ProfileAdapter(
    private val onSelect: (HostProfile) -> Unit,
    private val onEdit: (HostProfile) -> Unit,
    private val onDelete: (HostProfile) -> Unit,
) : ListAdapter<HostProfile, ProfileAdapter.Holder>(DIFF) {

    private var activeId: String? = null

    fun submit(list: List<HostProfile>, activeId: String?) {
        this.activeId = activeId
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == activeId)
    }

    inner class Holder(private val binding: ItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: HostProfile, isActive: Boolean) {
            binding.profileName.text =
                profile.name.ifBlank { binding.root.context.getString(R.string.unnamed_profile) }
            binding.profileHost.text = profile.hostUrl
            binding.profileActive.visibility = if (isActive) View.VISIBLE else View.GONE
            binding.profileEdit.setOnClickListener { onEdit(profile) }
            binding.profileDelete.setOnClickListener { onDelete(profile) }
            itemView.setOnClickListener { onSelect(profile) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HostProfile>() {
            override fun areItemsTheSame(oldItem: HostProfile, newItem: HostProfile) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: HostProfile, newItem: HostProfile) =
                oldItem == newItem
        }
    }
}

package com.wikiponder.touchmap.classes.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.NearestElement;

import java.util.ArrayList;
import java.util.List;

public class NodeAdapter extends RecyclerView.Adapter<NodeAdapter.NodeViewHolder> {
	
	private List<NearestElement> nearestElements = new ArrayList<>();
	private final OnNodeClickListener listener;
	private final Context context;
	
	/**
	 * Interface for handling click events on items in the RecyclerView.
	 */
	public interface OnNodeClickListener {
		void onNodeClick(MapElement element);
	}
	
	public NodeAdapter(Context context, OnNodeClickListener listener) {
		this.context = context;
		this.listener = listener;
	}
	
	@NonNull
	@Override
	public NodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_node, parent, false);
		return new NodeViewHolder(view);
	}
	
	@Override
	public void onBindViewHolder(@NonNull NodeViewHolder holder, int position) {
		NearestElement currentElement = nearestElements.get(position);
		holder.bind(currentElement, context, listener);
	}
	
	@Override
	public int getItemCount() {
		return nearestElements.size();
	}
	
	/**
	 * Updates the list of elements in the adapter and refreshes the RecyclerView.
	 * @param elements The new list of elements to display.
	 */
	@SuppressLint("NotifyDataSetChanged")
	public void setElements(List<NearestElement> elements) {
		this.nearestElements = elements;
		notifyDataSetChanged(); // Refreshes the entire list
	}
	
	/**
	 * The ViewHolder class for each item in the list.
	 */
	static class NodeViewHolder extends RecyclerView.ViewHolder {
		private final TextView nameTextView;
		private final TextView categoryTextView;
		private final TextView distanceTextView;
		
		public NodeViewHolder(@NonNull View itemView) {
			super(itemView);
			nameTextView = itemView.findViewById(R.id.node_name_textview);
			categoryTextView = itemView.findViewById(R.id.node_category_textview);
			distanceTextView = itemView.findViewById(R.id.node_distance_textview);
		}
		
		/**
		 * Binds a NearestElement's data to the views in the list item.
		 * @param nearestElement The element containing the data to display.
		 * @param context The application context.
		 * @param listener The click listener to attach to the item.
		 */
		public void bind(final NearestElement nearestElement, Context context, final OnNodeClickListener listener) {
			MapElement element = nearestElement.element;
			
			// Set the name, type, and formatted distance
			nameTextView.setText(element.getFormattedName(context, false));
			categoryTextView.setText(element.getTypeLabel(context));
			String distanceText = context.getString(R.string.node_distance_meters, nearestElement.distance);
			distanceTextView.setText(distanceText);
			
			// Set the click listener on the entire item view
			itemView.setOnClickListener(v -> listener.onNodeClick(element));
		}
	}
}
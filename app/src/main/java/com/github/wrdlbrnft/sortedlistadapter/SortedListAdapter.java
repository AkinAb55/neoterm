package com.github.wrdlbrnft.sortedlistadapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SortedList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A wrdlbrnft/SortedListAdapter könyvtár minimális, API-kompatibilis
 * újraimplementációja.
 * <p>
 * Az eredeti artefaktumok ({@code com.github.wrdlbrnft:sorted-list-adapter} és a
 * {@code modular-adapter} függősége) kizárólag a megszűnt jcenter/bintray-en
 * léteztek, git tag nélkül — így egyetlen élő Maven-repóból sem oldhatók fel.
 * Hogy a NeoTerm build végleg self-contained és determinisztikus legyen, a
 * ténylegesen használt felületet (ViewModel, ViewHolder, ComparatorBuilder,
 * Callback, edit()/add()/replaceAll()/commit()) az androidx {@link SortedList}-re
 * építve hozzuk magunkkal, az eredeti csomagnéven.
 * <p>
 * Szándékosan Java (és nem Kotlin): a Kotlin 1.4.32 frontend belső hibát
 * (KotlinFrontEndException / ErrorLexicalScope) dobott a beágyazott generikus
 * osztályok feloldásakor.
 */
public abstract class SortedListAdapter<T extends SortedListAdapter.ViewModel>
    extends RecyclerView.Adapter<SortedListAdapter.ViewHolder<?>> {

  /** A listában tárolt elemek által megvalósítandó interfész. */
  public interface ViewModel {
    <M> boolean isSameModelAs(M model);

    <M> boolean isContentTheSameAs(M model);
  }

  /** A RecyclerView nézet-tartója; a kötést a {@link #performBind(ViewModel)} végzi. */
  public abstract static class ViewHolder<M extends ViewModel> extends RecyclerView.ViewHolder {
    public ViewHolder(View itemView) {
      super(itemView);
    }

    public final void bind(M item) {
      performBind(item);
    }

    @SuppressWarnings("unchecked")
    final void bindInternal(ViewModel item) {
      performBind((M) item);
    }

    protected abstract void performBind(M item);
  }

  /** Szerkesztési életciklus-visszahívások (animációkhoz). */
  public interface Callback {
    void onEditStarted();

    void onEditFinished();
  }

  private final Comparator<T> comparator;
  private final SortedList<T> sortedList;
  private final List<Callback> callbacks = new ArrayList<>();

  public SortedListAdapter(Context context, Class<T> itemClass, Comparator<T> comparator) {
    this.comparator = comparator;
    this.sortedList = new SortedList<>(itemClass, new SortedList.Callback<T>() {
      @Override
      public int compare(T a, T b) {
        return SortedListAdapter.this.comparator.compare(a, b);
      }

      @Override
      public void onInserted(int position, int count) {
        notifyItemRangeInserted(position, count);
      }

      @Override
      public void onRemoved(int position, int count) {
        notifyItemRangeRemoved(position, count);
      }

      @Override
      public void onMoved(int fromPosition, int toPosition) {
        notifyItemMoved(fromPosition, toPosition);
      }

      @Override
      public void onChanged(int position, int count) {
        notifyItemRangeChanged(position, count);
      }

      @Override
      public boolean areContentsTheSame(T oldItem, T newItem) {
        return oldItem.isContentTheSameAs(newItem);
      }

      @Override
      public boolean areItemsTheSame(T item1, T item2) {
        return item1.isSameModelAs(item2);
      }
    });
  }

  public T getItem(int position) {
    return sortedList.get(position);
  }

  @Override
  public int getItemCount() {
    return sortedList.size();
  }

  @Override
  public final ViewHolder<?> onCreateViewHolder(ViewGroup parent, int viewType) {
    return onCreateViewHolder(LayoutInflater.from(parent.getContext()), parent, viewType);
  }

  public abstract ViewHolder<? extends T> onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType);

  @Override
  public final void onBindViewHolder(ViewHolder<?> holder, int position) {
    holder.bindInternal(getItem(position));
  }

  public void addCallback(Callback callback) {
    if (!callbacks.contains(callback)) {
      callbacks.add(callback);
    }
  }

  public void removeCallback(Callback callback) {
    callbacks.remove(callback);
  }

  public Editor edit() {
    return new Editor();
  }

  /**
   * Kötegelt módosító. A műveletek a {@link #commit()} híváskor, egyetlen
   * batched-update tranzakcióban érvényesülnek.
   */
  public class Editor {
    private final List<T> toAdd = new ArrayList<>();
    private List<T> replacement = null;
    private boolean replace = false;

    public Editor add(T item) {
      toAdd.add(item);
      return this;
    }

    public Editor add(List<T> items) {
      toAdd.addAll(items);
      return this;
    }

    public Editor replaceAll(List<T> items) {
      replacement = new ArrayList<>(items);
      replace = true;
      return this;
    }

    public void commit() {
      for (Callback callback : callbacks) {
        callback.onEditStarted();
      }
      sortedList.beginBatchedUpdates();
      try {
        if (replace) {
          sortedList.clear();
          if (replacement != null) {
            sortedList.addAll(replacement);
          }
        }
        if (!toAdd.isEmpty()) {
          sortedList.addAll(toAdd);
        }
      } finally {
        sortedList.endBatchedUpdates();
      }
      for (Callback callback : callbacks) {
        callback.onEditFinished();
      }
    }
  }

  /**
   * Típusonként eltérő rendezést felépítő builder. Egy adott {@link ViewModel}
   * altípushoz {@link Comparator}-t rendel; a {@link #build()}-elt comparator az
   * első illeszkedő szabályt alkalmazza.
   */
  public static class ComparatorBuilder<T> {
    private final List<Class<?>> modelClasses = new ArrayList<>();
    private final List<Comparator<?>> modelComparators = new ArrayList<>();

    public <M extends T> ComparatorBuilder<T> setOrderForModel(Class<M> modelClass, Comparator<M> comparator) {
      modelClasses.add(modelClass);
      modelComparators.add(comparator);
      return this;
    }

    public Comparator<T> build() {
      return new Comparator<T>() {
        @Override
        @SuppressWarnings("unchecked")
        public int compare(T a, T b) {
          for (int i = 0; i < modelClasses.size(); i++) {
            final Class<?> modelClass = modelClasses.get(i);
            if (modelClass.isInstance(a) && modelClass.isInstance(b)) {
              return ((Comparator<Object>) modelComparators.get(i)).compare(a, b);
            }
          }
          return 0;
        }
      };
    }
  }
}
